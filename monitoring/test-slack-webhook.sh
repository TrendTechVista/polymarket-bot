#!/bin/bash
#
# Test Slack Webhook Integration
# This script helps you verify your Slack webhook is working
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Polybot Slack Integration Tester${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if .env exists
if [ ! -f ".env" ]; then
    echo -e "${RED}❌ No .env file found${NC}"
    echo -e "${YELLOW}Creating from template...${NC}"
    cp .env.template .env
    echo ""
    echo -e "${YELLOW}Please edit monitoring/.env and add your Slack webhook URL:${NC}"
    echo -e "${YELLOW}  nano .env${NC}"
    echo ""
    echo -e "${YELLOW}Get your webhook URL from: https://api.slack.com/apps${NC}"
    echo -e "${YELLOW}Then run this script again.${NC}"
    exit 1
fi

# Load webhook URL
export $(grep SLACK_WEBHOOK_URL .env | xargs)

# Check if webhook is configured
if [ -z "$SLACK_WEBHOOK_URL" ] || [ "$SLACK_WEBHOOK_URL" == "https://hooks.slack.com/services/YOUR/WEBHOOK/URL" ]; then
    echo -e "${RED}❌ Slack webhook not configured${NC}"
    echo ""
    echo -e "${YELLOW}Follow these steps:${NC}"
    echo -e "${YELLOW}1. Go to: https://api.slack.com/apps${NC}"
    echo -e "${YELLOW}2. Create a new app (or select existing)${NC}"
    echo -e "${YELLOW}3. Enable 'Incoming Webhooks'${NC}"
    echo -e "${YELLOW}4. Add webhook to your workspace${NC}"
    echo -e "${YELLOW}5. Copy the webhook URL${NC}"
    echo -e "${YELLOW}6. Edit .env and paste the URL${NC}"
    echo ""
    exit 1
fi

echo -e "${GREEN}✅ Webhook URL found in .env${NC}"
echo -e "${BLUE}Webhook: ${SLACK_WEBHOOK_URL:0:50}...${NC}"
echo ""

# Test 1: Simple text message
echo -e "${BLUE}Test 1: Sending simple text message...${NC}"
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H 'Content-type: application/json' \
  --data '{"text":"🤖 *Polybot Test Alert*\n\nIf you see this message, your Slack integration is working correctly!\n\n✅ Webhook URL is valid\n✅ Alertmanager can send notifications\n✅ You are ready to monitor Polybot"}' \
  "$SLACK_WEBHOOK_URL")

if [ "$RESPONSE" == "200" ]; then
    echo -e "${GREEN}✅ Test message sent successfully!${NC}"
    echo -e "${GREEN}   Check your Slack channel for the message.${NC}"
else
    echo -e "${RED}❌ Failed to send message (HTTP $RESPONSE)${NC}"
    echo -e "${YELLOW}   Check that:${NC}"
    echo -e "${YELLOW}   - Webhook URL is correct (no extra spaces)${NC}"
    echo -e "${YELLOW}   - Webhook is active in Slack API${NC}"
    echo -e "${YELLOW}   - You have internet connectivity${NC}"
    exit 1
fi

echo ""
sleep 2

# Test 2: Formatted alert-style message
echo -e "${BLUE}Test 2: Sending formatted alert message...${NC}"
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H 'Content-type: application/json' \
  --data '{
    "attachments": [
      {
        "color": "warning",
        "title": "⚠️ Test Warning Alert",
        "text": "*Alert:* High CPU usage detected\n*Severity:* warning\n*Description:* CPU usage is 85% over the last 5 minutes\n*Time:* 2025-12-20 15:04:05",
        "footer": "Polybot Monitoring"
      }
    ]
  }' \
  "$SLACK_WEBHOOK_URL")

if [ "$RESPONSE" == "200" ]; then
    echo -e "${GREEN}✅ Formatted alert sent successfully!${NC}"
else
    echo -e "${YELLOW}⚠️  Formatted message failed but simple message worked${NC}"
    echo -e "${YELLOW}   This is OK - Alertmanager will use simple format${NC}"
fi

echo ""
sleep 2

# Test 3: Critical alert style
echo -e "${BLUE}Test 3: Sending critical alert message...${NC}"
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H 'Content-type: application/json' \
  --data '{
    "attachments": [
      {
        "color": "danger",
        "title": "🚨 Test CRITICAL Alert",
        "text": "*IMMEDIATE ACTION REQUIRED*\n\n*Alert:* Service Down\n*Description:* Strategy service has been down for more than 1 minute\n*Time:* 2025-12-20 15:04:05\n\n*Action Items:*\n1. Check service logs immediately\n2. Verify positions and exposure\n3. Consider stopping trading if risk-related",
        "footer": "Polybot Monitoring"
      }
    ]
  }' \
  "$SLACK_WEBHOOK_URL")

if [ "$RESPONSE" == "200" ]; then
    echo -e "${GREEN}✅ Critical alert sent successfully!${NC}"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Slack Integration Test Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Next Steps:${NC}"
echo -e "  1. Check your Slack channel - you should see 3 test messages"
echo -e "  2. If you see the messages, your integration is working!"
echo -e "  3. Start the monitoring stack: ${YELLOW}../start-monitoring.sh${NC}"
echo -e "  4. Alerts will automatically appear in Slack when conditions are met"
echo ""
echo -e "${BLUE}Useful Commands:${NC}"
echo -e "  ${YELLOW}# Check active alerts${NC}"
echo -e "  curl http://localhost:9090/api/v1/alerts | jq"
echo ""
echo -e "  ${YELLOW}# View Alertmanager status${NC}"
echo -e "  curl http://localhost:9093/api/v2/alerts | jq"
echo ""
echo -e "  ${YELLOW}# Silence alerts for 1 hour${NC}"
echo -e "  open http://localhost:9093/#/silences"
echo ""
