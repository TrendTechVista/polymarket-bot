# Polybot Slack Integration - Complete Setup Guide

## Step 1: Create Slack Channels

In your Slack workspace, create these channels:

### Required Channels

1. **#polybot-alerts** (General monitoring)
   - Click "+" next to Channels
   - Name: `polybot-alerts`
   - Description: "General Polybot monitoring alerts"
   - Make it Public

2. **#polybot-critical** (Urgent alerts - @here mentions)
   - Name: `polybot-critical`
   - Description: "🚨 CRITICAL alerts requiring immediate action"
   - Make it Public

3. **#polybot-risk** (PnL, exposure, drawdown)
   - Name: `polybot-risk`
   - Description: "⚠️ Risk management alerts (PnL, exposure, limits)"
   - Make it Public

4. **#polybot-trading** (Execution quality)
   - Name: `polybot-trading`
   - Description: "📈 Trading operations (fills, orders, slippage)"
   - Make it Public

5. **#polybot-data** (Data quality issues)
   - Name: `polybot-data`
   - Description: "🔌 Data quality alerts (WebSocket, TOB, stale data)"
   - Make it Public

### Optional but Recommended

6. **#polybot-logs** (Service logs - future expansion)
   - For structured logging output

7. **#polybot-dev** (Development/testing alerts)
   - For testing alerts before going live

## Step 2: Create Slack App and Get Webhook URL

### 2.1 Go to Slack API

1. Open: https://api.slack.com/apps
2. Click **"Create New App"**
3. Choose **"From scratch"**
4. App Name: `Polybot Alerting`
5. Workspace: Select your workspace
6. Click **"Create App"**

### 2.2 Enable Incoming Webhooks

1. In the left sidebar, click **"Incoming Webhooks"**
2. Toggle **"Activate Incoming Webhooks"** to ON
3. Scroll down and click **"Add New Webhook to Workspace"**
4. Select the channel: **#polybot-alerts** (we'll add more later)
5. Click **"Allow"**

### 2.3 Copy Webhook URLs

You'll see a webhook URL that looks like:
```
https://hooks.slack.com/services/T01234ABCDE/B01234FGHIJ/xYzAbC123456789
```

**IMPORTANT:** Copy this URL - you'll need it in Step 3.

### 2.4 Add Webhooks for Other Channels

Repeat Step 2.3 for each channel:
- #polybot-critical
- #polybot-risk
- #polybot-trading
- #polybot-data

**NOTE:** Slack will generate a DIFFERENT webhook URL for each channel!

## Step 3: Configure Environment File

### 3.1 Create .env File

```bash
cd /Users/antoniostano/programming/polybot/monitoring
cp .env.template .env
nano .env  # or use your favorite editor
```

### 3.2 Add Your Webhook URL

Edit the `.env` file:

```bash
# Polybot Monitoring Configuration

# Slack Integration - MAIN WEBHOOK
# This webhook posts to #polybot-alerts by default
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/ACTUAL/WEBHOOK

# Grafana Admin Credentials
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=change_this_to_something_secure_123

# Kafka Bootstrap Servers
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

**Replace** `YOUR/ACTUAL/WEBHOOK` with the webhook URL you copied.

### 3.3 Secure the .env File

```bash
# Make sure .env is not committed to git (it's already in .gitignore)
chmod 600 .env
```

## Step 4: Update Alertmanager Configuration (Multi-Channel)

If you want different channels for different alert types, you need to update Alertmanager with multiple webhook URLs.

### Option A: Single Channel (Simplest)

Keep the default configuration - all alerts go to #polybot-alerts.

✅ **This is fine for getting started!**

### Option B: Multi-Channel Routing (Advanced)

Edit `monitoring/alertmanager/alertmanager.yml`:

```yaml
receivers:
  - name: 'slack-general'
    slack_configs:
      - api_url: 'YOUR_WEBHOOK_FOR_polybot-alerts'
        channel: '#polybot-alerts'
        # ... rest of config

  - name: 'slack-critical'
    slack_configs:
      - api_url: 'YOUR_WEBHOOK_FOR_polybot-critical'
        channel: '#polybot-critical'
        # ... rest of config

  - name: 'slack-risk'
    slack_configs:
      - api_url: 'YOUR_WEBHOOK_FOR_polybot-risk'
        channel: '#polybot-risk'
        # ... rest of config
```

**For now, let's use Option A (single channel) to test.**

## Step 5: Test Slack Integration

### 5.1 Test Webhook Directly

```bash
# Test that your webhook works
curl -X POST \
  -H 'Content-type: application/json' \
  --data '{"text":"🤖 Test message from Polybot! If you see this, Slack integration is working!"}' \
  https://hooks.slack.com/services/YOUR/ACTUAL/WEBHOOK
```

**Expected Result:** You should see a message in your #polybot-alerts channel!

### 5.2 If It Works

✅ You should see a message from "Incoming Webhook" bot in #polybot-alerts
✅ The message says "Test message from Polybot!"

### 5.3 If It Doesn't Work

❌ Check that you copied the FULL webhook URL (starts with https://)
❌ Make sure you selected the correct channel when creating the webhook
❌ Verify the webhook is "Active" in https://api.slack.com/apps

## Step 6: Customize Slack App (Optional but Nice)

Make your alerts look professional:

### 6.1 Add App Icon

1. Go to https://api.slack.com/apps
2. Select "Polybot Alerting"
3. Click "Basic Information"
4. Scroll to "Display Information"
5. Upload an icon (robot emoji, chart icon, etc.)
6. App Name: `Polybot Alerts`
7. Short Description: `HFT trading system monitoring`
8. Background Color: `#1E88E5` (blue)

### 6.2 Change Bot Name

In `alertmanager.yml`, add:

```yaml
slack_configs:
  - api_url: '${SLACK_WEBHOOK_URL}'
    channel: '#polybot-alerts'
    username: 'Polybot Monitor'  # ← Custom name
    icon_emoji: ':robot_face:'    # ← Custom emoji
```

## Step 7: Start Monitoring Stack

```bash
cd /Users/antoniostano/programming/polybot

# Make script executable (if not already)
chmod +x start-monitoring.sh

# Start monitoring
./start-monitoring.sh
```

**Expected Output:**
```
========================================
  Polybot Monitoring Stack Startup
========================================

🔍 Checking if Polybot services are running...
✅ Prometheus is healthy
✅ Grafana is healthy
✅ Alertmanager is healthy

========================================
  Monitoring Stack Started Successfully!
========================================

Access URLs:
  📊 Grafana:       http://localhost:3000
                   Username: admin
                   Password: change_this_to_something_secure_123

  📈 Prometheus:    http://localhost:9090
  🔔 Alertmanager:  http://localhost:9093
```

## Step 8: Verify Alertmanager Loaded Config

```bash
# Check that Alertmanager loaded your webhook
docker logs polybot-alertmanager 2>&1 | grep -i "webhook\|slack"

# Check Alertmanager config
curl http://localhost:9093/api/v2/status | jq
```

## Step 9: Trigger a Test Alert

### Method 1: Simulate Service Down

```bash
# Stop executor service temporarily
# (Alertmanager will fire ServiceDown alert after 1 minute)
curl -X POST http://localhost:8080/actuator/shutdown
```

Wait 1-2 minutes, then check Slack!

### Method 2: Create Manual Test Alert

```bash
# Fire a test alert directly to Alertmanager
curl -H "Content-Type: application/json" -d '[{
  "labels": {
    "alertname": "TestAlert",
    "severity": "warning",
    "instance": "test"
  },
  "annotations": {
    "summary": "This is a test alert",
    "description": "If you see this in Slack, alerting is working!"
  }
}]' http://localhost:9093/api/v2/alerts
```

**Check Slack** - you should see the alert within 30 seconds!

## Step 10: Configure Alert Notifications

### Customize Who Gets Notified

In Slack:

1. Go to each channel (#polybot-critical, etc.)
2. Click the channel name at the top
3. Click "Integrations" tab
4. Find "Polybot Alerts" app
5. Click "Add people to get notified"
6. Add yourself (and team members)

### Set Up Mobile Notifications

1. Install Slack mobile app
2. Go to Settings → Notifications
3. For #polybot-critical:
   - Set to "All new messages"
   - Enable mobile push notifications
   - Enable notification sound
4. For #polybot-alerts:
   - Set to "Mentions & keywords"
   - Add keyword: "@here"

## Troubleshooting

### "Webhook URL validation failed"

- Check that you copied the FULL URL (no spaces, no line breaks)
- Verify webhook is active: https://api.slack.com/apps → Your App → Incoming Webhooks

### "No alerts appearing in Slack"

```bash
# Check Alertmanager logs
docker logs polybot-alertmanager --tail 50

# Check for errors
docker logs polybot-alertmanager 2>&1 | grep -i error

# Verify alert rules are loaded
curl http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[] | {alert: .name, state: .state}'

# Check active alerts
curl http://localhost:9090/api/v1/alerts | jq
```

### "Alerts stuck in Pending state"

- Alerts require condition to be true for duration specified in `for:` clause
- Example: `ServiceDown` requires service to be down for >1 minute
- Check condition: `curl 'http://localhost:9090/api/v1/query?query=up{job="executor-service"}'`

### "Wrong channel receiving alerts"

- Verify channel in alertmanager.yml matches webhook channel
- Each webhook URL is tied to ONE channel (set when creating webhook)
- Use different webhooks for different channels

## Next Steps

1. ✅ **Keep monitoring stack running** - it should auto-start with Docker
2. ✅ **Watch Grafana dashboards** - http://localhost:3000
3. ✅ **Test alert response** - When you get an alert, practice investigating
4. ✅ **Create runbooks** - Document what to do for each alert type
5. ✅ **Add team members** - Invite others to Slack channels

## Quick Reference: Important URLs

| Service | URL | Purpose |
|---------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards and visualization |
| Prometheus | http://localhost:9090 | Metrics and queries |
| Alertmanager | http://localhost:9093 | Alert status and silencing |
| Slack API | https://api.slack.com/apps | Manage webhooks |

## Emergency: Silence All Alerts

If you're getting spammed with alerts:

```bash
# Silence all alerts for 2 hours
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{
    "matchers": [{"name": "alertname", "value": ".*", "isRegex": true}],
    "startsAt": "'$(date -u +%Y-%m-%dT%H:%M:%S.000Z)'",
    "endsAt": "'$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%S.000Z)'",
    "createdBy": "emergency-silence",
    "comment": "Emergency silence - investigating issues"
  }' \
  http://localhost:9093/api/v2/silences
```

Or use the Alertmanager UI: http://localhost:9093/#/silences
