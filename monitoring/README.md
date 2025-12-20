# Polybot Monitoring Stack

Comprehensive monitoring and alerting infrastructure for the Polybot HFT system using Prometheus, Grafana, and Alertmanager.

## Architecture

```
┌─────────────────┐
│ Spring Boot     │
│ Services        │──► Expose metrics at /actuator/prometheus
│ (8080-8083)     │
└─────────────────┘
        │
        ▼
┌─────────────────┐
│ Prometheus      │──► Scrape metrics every 15s
│ (9090)          │──► Evaluate alert rules
└─────────────────┘
        │
        ├──► ┌─────────────────┐
        │    │ Grafana (3000)  │──► Visualize dashboards
        │    └─────────────────┘
        │
        └──► ┌─────────────────┐
             │ Alertmanager    │──► Route alerts to Slack
             │ (9093)          │
             └─────────────────┘
```

## Quick Start

### 1. Prerequisites

- Docker and Docker Compose installed
- Slack webhook URL (for alerts)
- All Polybot services running (executor, strategy, ingestor, analytics)

### 2. Configure Slack Integration

Create a `.env` file in the `monitoring/` directory:

```bash
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/WEBHOOK/URL
GRAFANA_ADMIN_PASSWORD=your_secure_password
```

**How to get Slack Webhook URL:**
1. Go to https://api.slack.com/apps
2. Create a new app or select existing
3. Enable "Incoming Webhooks"
4. Add webhook to workspace
5. Copy the webhook URL

**Create these Slack channels:**
- `#polybot-alerts` - General monitoring alerts
- `#polybot-critical` - Critical alerts requiring immediate action
- `#polybot-risk` - Risk management alerts (PnL, exposure, drawdown)
- `#polybot-trading` - Trading operations (fills, orders, slippage)
- `#polybot-data` - Data quality issues (WebSocket, TOB, stale data)

### 3. Start Monitoring Stack

```bash
# From project root
cd /Users/antoniostano/programming/polybot

# Start monitoring infrastructure
docker compose -f docker-compose.monitoring.yaml up -d

# Verify all containers are running
docker ps | grep polybot
```

### 4. Access UIs

| Service | URL | Credentials |
|---------|-----|-------------|
| **Grafana** | http://localhost:3000 | admin / polybot123 (or your .env password) |
| **Prometheus** | http://localhost:9090 | No auth |
| **Alertmanager** | http://localhost:9093 | No auth |

### 5. Verify Metrics Collection

```bash
# Check Prometheus is scraping services
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# Check executor service exposes Prometheus metrics
curl http://localhost:8080/actuator/prometheus | head -20

# Check strategy service
curl http://localhost:8081/actuator/prometheus | head -20
```

## Dashboards

### Polybot - Trading Overview

**URL:** http://localhost:3000/d/polybot-trading

**Panels:**
- 📊 Realized PnL (USD) - Daily P&L with color thresholds
- 💰 Unrealized PnL (USD) - Current mark-to-market
- 🎯 Total Exposure - Gauge showing % of bankroll at risk
- 📈 Orders per Minute - Trading velocity
- 📊 Cumulative PnL Over Time - Line chart of total PnL
- ⚖️ Inventory Imbalance - Complete-set hedging health
- 📋 Order Status Breakdown - Pie chart (filled/cancelled/rejected)
- ⚡ Complete-Set Edge - Current arbitrage opportunity
- 🎯 Active Markets - Number of markets being traded
- 📊 Fill Rate - % of orders that execute
- ⏱️ Average Slippage - Execution quality metric
- 🌐 WebSocket Status - Market data connection health
- 💻 Service Health - All microservices up/down status

## Alert Rules

### Critical Alerts (Immediate Action Required)

| Alert | Condition | Action |
|-------|-----------|--------|
| **ServiceDown** | Service unavailable >1min | Restart service, check logs |
| **DailyLossLimitBreached** | Daily PnL < -$100 | STOP TRADING, review positions |
| **ExposureLimitBreached** | Exposure > 20% bankroll | Stop new orders, reduce positions |
| **WebSocketDisconnected** | Market WS down >2min | Restart ingestor, verify network |
| **MarketDiscoveryFailure** | 0 active markets | Check Polymarket API, verify market schedule |

### Warning Alerts (Monitor Closely)

| Alert | Condition | Investigation |
|-------|-----------|---------------|
| **UnexpectedDrawdown** | Unrealized PnL < -$100 | Check positions, market volatility |
| **InventoryImbalanceHigh** | abs(imbalance) > 100 | Verify fast top-ups working |
| **HighSlippage** | Avg slippage > 5 ticks | Check liquidity, quote aggressiveness |
| **LowFillRate** | Fill rate < 50% | Adjust quote prices, check competition |
| **StaleMarketData** | No updates >60s | Restart WS connection |

## Custom Metrics (Java Implementation)

### Adding Custom Metrics to Strategy

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;

@Service
public class GabagoolDirectionalEngine {

    private final Counter ordersPlaced;
    private final Gauge unrealizedPnl;

    public GabagoolDirectionalEngine(MeterRegistry registry) {
        // Counter - monotonically increasing
        this.ordersPlaced = Counter.builder("polybot_orders_total")
            .tag("strategy", "gabagool")
            .tag("type", "maker")
            .description("Total orders placed by strategy")
            .register(registry);

        // Gauge - current value
        this.unrealizedPnl = Gauge.builder("polybot_strategy_unrealized_pnl_usd",
                                           this::calculateUnrealizedPnl)
            .description("Current unrealized PnL in USD")
            .register(registry);
    }

    public void placeOrder(Order order) {
        // ... order placement logic
        ordersPlaced.increment();
    }

    private double calculateUnrealizedPnl() {
        // Calculate current P&L from positions
        return positions.stream()
            .mapToDouble(p -> (p.currentPrice - p.entryPrice) * p.size)
            .sum();
    }
}
```

### Metric Naming Convention

```
polybot_{component}_{metric}_{unit}

Examples:
- polybot_orders_total                   (counter)
- polybot_orders_filled_total            (counter)
- polybot_strategy_unrealized_pnl_usd    (gauge)
- polybot_order_slippage_ticks           (gauge)
- polybot_gabagool_complete_set_edge     (gauge)
- polybot_websocket_connected            (gauge, 0 or 1)
```

## Troubleshooting

### Prometheus not scraping services

```bash
# Check if services expose /actuator/prometheus
curl http://localhost:8080/actuator/prometheus

# If 404, verify:
# 1. micrometer-registry-prometheus dependency in pom.xml
# 2. management.endpoints.web.exposure.include: prometheus in application.yaml
# 3. Service restarted after config change

# Check Prometheus targets page
open http://localhost:9090/targets
```

### Alerts not firing

```bash
# Check alert rules loaded
curl http://localhost:9090/api/v1/rules | jq

# Check active alerts
curl http://localhost:9090/api/v1/alerts | jq

# Verify Alertmanager receiving alerts
curl http://localhost:9093/api/v2/alerts | jq
```

### Slack notifications not working

```bash
# Test webhook directly
curl -X POST \
  -H 'Content-type: application/json' \
  --data '{"text":"Test alert from Polybot"}' \
  YOUR_SLACK_WEBHOOK_URL

# Check Alertmanager config
docker exec polybot-alertmanager cat /etc/alertmanager/alertmanager.yml

# Check Alertmanager logs
docker logs polybot-alertmanager --tail 50
```

### Grafana dashboard shows "No Data"

```bash
# Verify Prometheus datasource
curl http://localhost:3000/api/datasources | jq

# Test query directly in Prometheus
curl 'http://localhost:9090/api/v1/query?query=up' | jq

# Check Grafana logs
docker logs polybot-grafana --tail 50
```

## Production Deployment

### Security Hardening

1. **Change default passwords:**
   ```yaml
   environment:
     - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}
   ```

2. **Enable authentication:**
   ```yaml
   # prometheus.yml
   basic_auth:
     username: 'polybot'
     password_file: '/etc/prometheus/password'
   ```

3. **Use HTTPS:**
   - Add nginx reverse proxy
   - Configure TLS certificates
   - Force HTTPS redirects

4. **Network isolation:**
   ```yaml
   networks:
     polybot-monitoring:
       internal: true  # No external access
   ```

### Scaling

For high-volume production:

1. **Increase retention:**
   ```yaml
   command:
     - '--storage.tsdb.retention.time=90d'  # 90 days instead of 30
   ```

2. **Add remote storage (e.g., Thanos, Cortex):**
   ```yaml
   remote_write:
     - url: "http://thanos:19291/api/v1/receive"
   ```

3. **Separate Alertmanager:**
   - Deploy Alertmanager cluster (HA)
   - Use external secret management (Vault)

## Maintenance

### Backup Dashboards

```bash
# Export all dashboards
for dash in $(curl -s http://admin:polybot123@localhost:3000/api/search | jq -r '.[].uid'); do
  curl -s http://admin:polybot123@localhost:3000/api/dashboards/uid/$dash | jq .dashboard > dashboards/backup-$dash.json
done
```

### Cleanup Old Data

```bash
# Prometheus automatically deletes old data based on retention
# Manual cleanup:
docker exec polybot-prometheus promtool tsdb list /prometheus
docker exec polybot-prometheus promtool tsdb clean --db.path=/prometheus
```

### Update Stack

```bash
# Pull latest images
docker compose -f docker-compose.monitoring.yaml pull

# Restart services
docker compose -f docker-compose.monitoring.yaml up -d
```

## Further Reading

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Dashboard Best Practices](https://grafana.com/docs/grafana/latest/dashboards/)
- [Spring Boot Actuator Metrics](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics)
- [Alertmanager Routing](https://prometheus.io/docs/alerting/latest/configuration/)
