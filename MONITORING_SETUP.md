# Monitoring Setup - Complete Guide

## What We Built

A **Spring Boot microservice** (`monitoring-orchestrator-service`) that automatically manages your Prometheus + Grafana + Alertmanager monitoring stack as part of the Polybot application lifecycle.

### The Problem This Solves

Previously, you had to manually run `docker-compose up` to start monitoring, and remember to stop it separately. Now, monitoring infrastructure is managed as **just another service** in your application.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Polybot Services                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  executor-service (8080)                                    │
│  strategy-service (8081)                                    │
│  ingestor-service (8082)                                    │
│  monitoring-orchestrator-service (8084) ──┐                │
│                                            │                │
└────────────────────────────────────────────┼────────────────┘
                                             │
                                             │ Manages lifecycle
                                             │
                         ┌───────────────────▼──────────────────┐
                         │  Docker Compose Monitoring Stack     │
                         ├──────────────────────────────────────┤
                         │  • Prometheus (9090)                 │
                         │  • Grafana (3000)                    │
                         │  • Alertmanager (9093)               │
                         │  • Node Exporter (9100)              │
                         └──────────────────────────────────────┘
```

## Quick Start

### 1. Start Everything
```bash
./start-all-services.sh
```

This starts:
- monitoring-orchestrator-service (which starts Prometheus, Grafana, Alertmanager)
- executor-service
- strategy-service
- ingestor-service

### 2. Access Monitoring
- **Grafana Dashboard**: http://localhost:3000 (admin/polybot123)
- **Prometheus**: http://localhost:9090
- **Alertmanager**: http://localhost:9093

### 3. Check Health
```bash
curl http://localhost:8084/api/monitoring/status
```

### 4. Stop Everything
```bash
./stop-all-services.sh
```

This stops all services **and** automatically stops the monitoring stack.

## What Happens During Startup

1. **monitoring-orchestrator-service starts** (port 8084)
2. **@PostConstruct hook triggers** → `DockerComposeLifecycleManager.startMonitoringStack()`
3. **Executes**: `docker compose -f docker-compose.monitoring.yaml up -d`
4. **Waits**: Polls every 5 seconds (up to 60 seconds) for all 4 services to be healthy
5. **Validates**: Ensures Prometheus, Grafana, Alertmanager, Node Exporter are all running
6. **Reports ready**: Service health endpoint shows UP

```
2025-12-20 14:20:00 INFO  Starting monitoring stack lifecycle manager
2025-12-20 14:20:00 INFO  Found docker-compose file at: .../docker-compose.monitoring.yaml
2025-12-20 14:20:00 INFO  Executing: docker compose up -d
2025-12-20 14:20:05 INFO  docker-compose: ✔ Container prometheus Started
2025-12-20 14:20:05 INFO  docker-compose: ✔ Container grafana Started
2025-12-20 14:20:05 INFO  docker-compose: ✔ Container alertmanager Started
2025-12-20 14:20:05 INFO  docker-compose: ✔ Container node-exporter Started
2025-12-20 14:20:15 INFO  All monitoring services are healthy after 10 seconds
2025-12-20 14:20:15 INFO  ✓ Monitoring stack is UP and READY
```

## What Happens During Shutdown

1. **Service receives shutdown signal** (SIGTERM or Ctrl+C)
2. **@PreDestroy hook triggers** → `DockerComposeLifecycleManager.stopMonitoringStack()`
3. **Executes**: `docker compose -f docker-compose.monitoring.yaml down`
4. **Cleanup**: All monitoring containers are stopped and removed
5. **Service exits cleanly**

```
2025-12-20 16:30:00 INFO  Stopping monitoring stack
2025-12-20 16:30:00 INFO  Executing: docker compose down
2025-12-20 16:30:05 INFO  docker-compose: Container prometheus Stopped
2025-12-20 16:30:05 INFO  docker-compose: Container grafana Stopped
2025-12-20 16:30:05 INFO  docker-compose: Container alertmanager Stopped
2025-12-20 16:30:05 INFO  docker-compose: Container node-exporter Stopped
2025-12-20 16:30:05 INFO  ✓ Monitoring stack stopped successfully
```

## REST API

The orchestrator service exposes control endpoints:

### Get Status
```bash
curl http://localhost:8084/api/monitoring/status
```
```json
{
  "managed": true,
  "totalServices": 4,
  "runningServices": 4,
  "healthStatus": "HEALTHY"
}
```

### Restart Monitoring Stack
```bash
curl -X POST http://localhost:8084/api/monitoring/restart
```

### Get Quick Links
```bash
curl http://localhost:8084/api/monitoring/links
```
```json
{
  "grafana": "http://localhost:3000 (admin/polybot123)",
  "prometheus": "http://localhost:9090",
  "alertmanager": "http://localhost:9093",
  "this_service": "http://localhost:8084/actuator/health"
}
```

## Health Checks

The service integrates with Spring Boot Actuator:

```bash
curl http://localhost:8084/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "monitoringStack": {
      "status": "UP",
      "details": {
        "managed": true,
        "totalServices": 4,
        "runningServices": 4,
        "healthStatus": "HEALTHY",
        "prometheus": "http://localhost:9090",
        "grafana": "http://localhost:3000",
        "alertmanager": "http://localhost:9093"
      }
    }
  }
}
```

## Configuration

File: `monitoring-orchestrator-service/src/main/resources/application.yaml`

```yaml
server:
  port: 8084

monitoring:
  docker-compose:
    file-path: ${POLYBOT_HOME:..}/docker-compose.monitoring.yaml
    project-name: polybot-monitoring
    startup-timeout-seconds: 60
    health-check-interval-seconds: 5
```

## Key Components

### 1. DockerComposeLifecycleManager
**File**: `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/service/DockerComposeLifecycleManager.java:33`

Core lifecycle management:
- `@PostConstruct startMonitoringStack()` - Starts Docker Compose stack on service startup
- `@PreDestroy stopMonitoringStack()` - Stops Docker Compose stack on service shutdown
- `waitForStackReadiness()` - Polls for all services to be healthy
- `getStackStatus()` - Returns current monitoring stack status

### 2. MonitoringController
**File**: `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/controller/MonitoringController.java:16`

REST API endpoints:
- `GET /api/monitoring/status` - Get monitoring stack status
- `POST /api/monitoring/restart` - Restart monitoring stack
- `GET /api/monitoring/health` - Health check
- `GET /api/monitoring/links` - Get quick access links

### 3. MonitoringStackHealthIndicator
**File**: `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/health/MonitoringStackHealthIndicator.java:16`

Custom health indicator for Spring Boot Actuator integration.

## Troubleshooting

### Service won't start
```bash
# Check Docker is running
docker ps

# Check docker-compose file exists
ls -la docker-compose.monitoring.yaml

# Check logs
tail -f logs/monitoring-orchestrator-service.log
```

### Monitoring stack not coming up
```bash
# Check Docker logs
docker compose -f docker-compose.monitoring.yaml logs

# Manually test
docker compose -f docker-compose.monitoring.yaml up -d

# Check port availability
lsof -i :9090,3000,9093,9100
```

### Service hangs during startup
- Increase timeout: Edit `application.yaml` → `monitoring.docker-compose.startup-timeout-seconds: 120`
- Check Docker resource limits (CPU/memory)

## Benefits

✅ **Application-level integration**: Monitoring is part of your app, not a separate operational concern

✅ **Automatic lifecycle**: Start your service, monitoring starts. Stop your service, monitoring stops.

✅ **Health checks**: Built-in health indicators show monitoring stack status

✅ **Graceful shutdown**: No orphaned Docker containers

✅ **REST API**: Manual control when needed

✅ **Consistent experience**: Same pattern as other Polybot microservices

## Next Steps

1. **Test it**: Run `./start-all-services.sh` and verify all services + monitoring come up
2. **Customize**: Edit alert rules in `monitoring/prometheus/alerts.yml`
3. **Add metrics**: Instrument your code with Micrometer counters/gauges/timers
4. **Create dashboards**: Build custom Grafana dashboards for your specific metrics
5. **Set up notifications**: Configure Slack channels in `monitoring/alertmanager/alertmanager.yml`

## Files Created

### Service Code
- `monitoring-orchestrator-service/pom.xml`
- `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/MonitoringOrchestratorApplication.java`
- `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/config/MonitoringProperties.java`
- `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/service/DockerComposeLifecycleManager.java`
- `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/controller/MonitoringController.java`
- `monitoring-orchestrator-service/src/main/java/com/polybot/monitoring/orchestrator/health/MonitoringStackHealthIndicator.java`
- `monitoring-orchestrator-service/src/main/resources/application.yaml`

### Scripts
- `start-monitoring-orchestrator.sh` - Start just the orchestrator service
- `start-all-services.sh` - Start all Polybot services including monitoring
- `stop-all-services.sh` - Stop all services cleanly

### Documentation
- `monitoring-orchestrator-service/README.md` - Service-specific documentation
- `MONITORING_SETUP.md` - This file

### Modified
- `pom.xml` - Added `monitoring-orchestrator-service` module
