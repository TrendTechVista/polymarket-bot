#!/bin/bash
set -e

echo "🚀 Starting Polybot Monitoring Stack..."
echo ""

# Check if .env exists
if [ ! -f "monitoring/.env" ]; then
    echo "❌ No monitoring/.env file found"
    echo "Please create it first with your Slack webhook URL"
    exit 1
fi

# Start monitoring stack
docker compose -f docker-compose.monitoring.yaml up -d

echo ""
echo "✅ Monitoring stack started!"
echo ""
echo "Access URLs:"
echo "  📊 Grafana:       http://localhost:3000"
echo "  📈 Prometheus:    http://localhost:9090"
echo "  🔔 Alertmanager:  http://localhost:9093"
echo ""
echo "Default Grafana login:"
echo "  Username: admin"
echo "  Password: polybot123"
echo ""
