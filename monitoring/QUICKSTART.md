# 🚀 Polybot Monitoring - 5-Minute Quick Start

The absolute fastest way to get monitoring and alerting working.

## Prerequisites ✅

- [x] Slack account (you have this!)
- [x] New Slack workspace created
- [x] Docker Desktop running
- [x] Polybot services built (executor, strategy, etc.)

## Quick Setup (5 minutes)

### 1️⃣ Create Slack Webhook (2 minutes)

**A. Go to Slack API:**
```
https://api.slack.com/apps
```

**B. Create App:**
- Click "Create New App"
- Choose "From scratch"
- App Name: `Polybot`
- Select your workspace
- Click "Create App"

**C. Enable Webhooks:**
- Left sidebar → "Incoming Webhooks"
- Toggle ON
- Scroll down → "Add New Webhook to Workspace"
- Select channel: **#general** (or create **#polybot-alerts**)
- Click "Allow"

**D. Copy Webhook URL:**
You'll see something like:
```
https://hooks.slack.com/services/T01234ABCDE/B01234FGHIJ/xYzAbC123456789
```
**COPY THIS!** You need it for the next step.

---

### 2️⃣ Configure Polybot (1 minute)

**A. Open terminal and navigate to monitoring folder:**
```bash
cd /Users/antoniostano/programming/polybot/monitoring
```

**B. Create .env file:**
```bash
cp .env.template .env
nano .env
```

**C. Paste your webhook URL:**
```bash
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/ACTUAL/WEBHOOK
GRAFANA_ADMIN_PASSWORD=mypassword123
```

**Save:** Ctrl+O, Enter, Ctrl+X

---

### 3️⃣ Test Slack Connection (30 seconds)

```bash
cd /Users/antoniostano/programming/polybot/monitoring
./test-slack-webhook.sh
```

**Expected output:**
```
========================================
  Polybot Slack Integration Tester
========================================

✅ Webhook URL found in .env
Test 1: Sending simple text message...
✅ Test message sent successfully!
   Check your Slack channel for the message.
```

**Check Slack** - you should see test messages! 🎉

---

### 4️⃣ Start Monitoring Stack (1 minute)

```bash
cd /Users/antoniostano/programming/polybot
./start-monitoring.sh
```

**Expected output:**
```
========================================
  Polybot Monitoring Stack Started Successfully!
========================================

Access URLs:
  📊 Grafana:       http://localhost:3000
  📈 Prometheus:    http://localhost:9090
  🔔 Alertmanager:  http://localhost:9093
```

---

### 5️⃣ View Dashboards (30 seconds)

**A. Open Grafana:**
```
http://localhost:3000
```

**Login:**
- Username: `admin`
- Password: (whatever you set in .env)

**B. Navigate to Dashboard:**
- Click "Dashboards" in left sidebar
- Click "Polybot - Trading Overview"

**C. You should see:**
- 📊 PnL metrics
- 🎯 Exposure gauges
- 📈 Order statistics
- ⚡ Complete-set edge
- 💻 Service health

---

## ✅ Verification Checklist

Run these commands to verify everything is working:

```bash
# 1. Check Prometheus is scraping services
curl -s http://localhost:9090/api/v1/targets | \
  jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# Expected: All services show "up"

# 2. Check executor exposes metrics
curl -s http://localhost:8080/actuator/prometheus | grep -c "polybot"

# Expected: Number > 0 (means metrics are exposed)

# 3. Check strategy exposes metrics
curl -s http://localhost:8081/actuator/prometheus | grep -c "polybot"

# Expected: Number > 0

# 4. Check alert rules are loaded
curl -s http://localhost:9090/api/v1/rules | \
  jq '.data.groups[].rules | length'

# Expected: Array of numbers (shows alert rules loaded)

# 5. Check Alertmanager is running
curl -s http://localhost:9093/api/v2/status | jq '.cluster.status'

# Expected: "ready"
```

---

## 🧪 Trigger a Test Alert

**Method 1: Fire a manual test alert**

```bash
curl -H "Content-Type: application/json" -d '[{
  "labels": {
    "alertname": "TestAlert",
    "severity": "warning",
    "category": "testing"
  },
  "annotations": {
    "summary": "This is a test alert",
    "description": "Testing end-to-end alerting pipeline. If you see this in Slack, everything works!"
  }
}]' http://localhost:9093/api/v2/alerts
```

**Expected:** Alert appears in Slack within 30-60 seconds

**Method 2: Simulate high memory usage**

```bash
# This will trigger a warning alert if executor memory > 2GB
# (Only if your executor is actually using that much memory)
```

---

## 📱 Set Up Mobile Notifications

**For critical alerts:**

1. Install Slack mobile app
2. Go to your workspace
3. Find **#polybot-alerts** channel
4. Tap channel name → Notifications
5. Set to: **"All new messages"**
6. Enable push notifications

**When critical alerts fire, you'll get push notifications on your phone!**

---

## 🎯 What Alerts Will You Get?

### Critical (Immediate Action)

| Alert | When It Fires | What To Do |
|-------|---------------|------------|
| **ServiceDown** | Service unavailable >1min | Check logs, restart service |
| **DailyLossLimitBreached** | Daily PnL < -$100 | STOP TRADING, review |
| **ExposureLimitBreached** | Exposure > 20% bankroll | Stop new orders |
| **WebSocketDisconnected** | Market data down >2min | Restart ingestor |

### Warning (Monitor)

| Alert | When It Fires | What To Do |
|-------|---------------|------------|
| **HighSlippage** | Avg slippage > 5 ticks | Check execution quality |
| **LowFillRate** | Fill rate < 50% | Adjust quote prices |
| **InventoryImbalanceHigh** | Imbalance > 100 shares | Verify hedging |

---

## 🛠️ Troubleshooting

### "No test message in Slack"

```bash
# Test webhook directly
curl -X POST \
  -H 'Content-type: application/json' \
  --data '{"text":"Direct test"}' \
  YOUR_WEBHOOK_URL

# If this fails, your webhook URL is wrong
# Go back to https://api.slack.com/apps and check it
```

### "Prometheus shows targets as DOWN"

```bash
# Check if services are actually running
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# If 404, services aren't running
./start-all.sh
```

### "Grafana dashboard shows 'No Data'"

```bash
# Check Prometheus can query metrics
curl 'http://localhost:9090/api/v1/query?query=up' | jq

# Should show metrics for all services
```

### "Alerts not firing"

```bash
# Check if any alerts are active
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts'

# Check Alertmanager received them
curl http://localhost:9093/api/v2/alerts | jq

# Check Alertmanager logs
docker logs polybot-alertmanager --tail 50
```

---

## 🎉 Success! What's Next?

### Immediate (First Hour)

1. ✅ Watch Grafana dashboard - familiarize yourself with panels
2. ✅ Trigger a few test alerts - practice responding
3. ✅ Check Slack - make sure notifications are working

### Short-term (First Day)

1. ✅ Create separate channels: #polybot-critical, #polybot-risk
2. ✅ Add multiple webhooks for different alert types
3. ✅ Customize alert thresholds (edit monitoring/prometheus/alerts.yml)
4. ✅ Invite team members to Slack channels

### Medium-term (First Week)

1. ✅ Monitor alert frequency - tune to reduce false positives
2. ✅ Create runbooks - document what to do for each alert
3. ✅ Add custom metrics to your strategy code
4. ✅ Build additional Grafana dashboards

### Long-term (Production)

1. ✅ Set up PagerDuty for on-call rotation
2. ✅ Enable Prometheus remote storage (Thanos)
3. ✅ Add compliance logging
4. ✅ Implement auto-remediation (restart services on failure)

---

## 📚 Additional Resources

- **Full Setup Guide:** `monitoring/SLACK_SETUP_GUIDE.md`
- **Detailed README:** `monitoring/README.md`
- **Alert Rules Reference:** `monitoring/prometheus/alerts.yml`
- **Dashboard JSON:** `monitoring/grafana/dashboards/polybot-trading-overview.json`

---

## 🆘 Need Help?

**Check logs:**
```bash
docker logs polybot-prometheus -f
docker logs polybot-grafana -f
docker logs polybot-alertmanager -f
```

**Restart monitoring stack:**
```bash
docker compose -f docker-compose.monitoring.yaml restart
```

**Stop monitoring stack:**
```bash
docker compose -f docker-compose.monitoring.yaml down
```

**Complete reset:**
```bash
docker compose -f docker-compose.monitoring.yaml down -v
./start-monitoring.sh
```

---

## 🎯 Quick Reference

| What | Where |
|------|-------|
| **Grafana** | http://localhost:3000 |
| **Prometheus** | http://localhost:9090 |
| **Alertmanager** | http://localhost:9093 |
| **Slack API** | https://api.slack.com/apps |
| **Test webhook** | `monitoring/test-slack-webhook.sh` |
| **Config file** | `monitoring/.env` |
| **Alert rules** | `monitoring/prometheus/alerts.yml` |

---

**You're all set! Start trading and stay informed!** 🚀📊
