# Monitoring Orchestrator Service

**Spring Boot service that manages the Docker Compose monitoring stack lifecycle**

## Overview

This service automatically starts and manages the Prometheus + Grafana + Alertmanager monitoring stack when it starts up, and gracefully shuts it down when the service stops. This integrates monitoring infrastructure management directly into the Polybot application lifecycle.

## Architecture

```
┌─────────────────────────────────────────┐
│  monitoring-orchestrator-service        │
│  (Spring Boot on port 8084)             │
│                                         │
│  @PostConstruct: docker compose up -d   │
│  @PreDestroy: docker compose down       │
└─────────────────────────────────────────┘
           │
           ├─ Manages ──────────────────┐
           │                             ▼
    ┌──────────────────────────────────────────┐
    │   Docker Compose Monitoring Stack        │
    ├──────────────────────────────────────────┤
    │  • Prometheus (port 9090)                │
    │  • Grafana (port 3000)                   │
    │  • Alertmanager (port 9093)              │
    │  • Node Exporter (port 9100)             │
    └──────────────────────────────────────────┘
```

## Key Features

- **Automatic Lifecycle Management**: Monitoring stack starts when service starts, stops when service stops
- **Health Checks**: Built-in health indicators for monitoring stack status
- **REST API**: Manual control endpoints for restart and status
- **Startup Validation**: Waits for all services to be healthy before marking as ready
- **Graceful Shutdown**: Proper cleanup of Docker containers on service shutdown

## REST API Endpoints

### Get Status
```bash
curl http://localhost:8084/api/monitoring/status
```
Response:
```json
{
  "managed": true,
  "totalServices": 4,
  "runningServices": 4,
  "healthStatus": "HEALTHY"
}
```

### Health Check
```bash
curl http://localhost:8084/api/monitoring/health
```

### Restart Stack
```bash
curl -X POST http://localhost:8084/api/monitoring/restart
```

### Get Monitoring Links
```bash
curl http://localhost:8084/api/monitoring/links
```
Response:
```json
{
  "grafana": "http://localhost:3000 (admin/${GRAFANA_ADMIN_PASSWORD})",
  "prometheus": "http://localhost:9090",
  "alertmanager": "http://localhost:9093",
  "this_service": "http://localhost:8084/actuator/health"
}
```

## Configuration

In `application.yaml`:

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

## Running the Service

### Build
```bash
mvn clean package -pl monitoring-orchestrator-service -am
```

### Run
```bash
java -jar monitoring-orchestrator-service/target/monitoring-orchestrator-service-0.0.1-SNAPSHOT.jar
```

Or use the startup script:
```bash
./start-monitoring-orchestrator.sh
```

## Startup Sequence

1. **Service starts** → Spring Boot application initialization
2. **@PostConstruct** → DockerComposeLifecycleManager.startMonitoringStack()
3. **Validation** → Checks docker-compose.monitoring.yaml exists
4. **Execute** → `docker compose up -d`
5. **Wait** → Polls for all 4 services to be healthy (up to 60s)
6. **Ready** → Service reports healthy, monitoring stack is UP

## Shutdown Sequence

1. **Service stops** → Shutdown hook triggered
2. **@PreDestroy** → DockerComposeLifecycleManager.stopMonitoringStack()
3. **Execute** → `docker compose down`
4. **Cleanup** → All monitoring containers stopped and removed

## Health Indicator

Registered at `/actuator/health` with custom `monitoringStack` component:

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

## Logging

All Docker Compose output is logged at INFO level:
```
2025-12-20 14:20:00 INFO  DockerComposeLifecycleManager : Starting monitoring stack lifecycle manager
2025-12-20 14:20:00 INFO  DockerComposeLifecycleManager : Found docker-compose file at: /Users/.../docker-compose.monitoring.yaml
2025-12-20 14:20:00 INFO  DockerComposeLifecycleManager : Executing: docker compose up -d
2025-12-20 14:20:05 INFO  DockerComposeLifecycleManager : docker-compose: [+] Running 4/4
2025-12-20 14:20:05 INFO  DockerComposeLifecycleManager : docker-compose: ✔ Container prometheus Started
2025-12-20 14:20:05 INFO  DockerComposeLifecycleManager : docker-compose: ✔ Container grafana Started
2025-12-20 14:20:05 INFO  DockerComposeLifecycleManager : docker-compose: ✔ Container alertmanager Started
2025-12-20 14:20:05 INFO  DockerComposeLifecycleManager : docker-compose: ✔ Container node-exporter Started
2025-12-20 14:20:15 INFO  DockerComposeLifecycleManager : All monitoring services are healthy after 10 seconds
2025-12-20 14:20:15 INFO  DockerComposeLifecycleManager : ✓ Monitoring stack is UP and READY
```

## Integration with Other Services

This service should be started alongside your other Polybot services:

```bash
# Start all services including monitoring
java -jar executor-service/target/executor-service-0.0.1-SNAPSHOT.jar &
java -jar strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar &
java -jar ingestor-service/target/ingestor-service-0.0.1-SNAPSHOT.jar &
java -jar monitoring-orchestrator-service/target/monitoring-orchestrator-service-0.0.1-SNAPSHOT.jar &
```

When all services are running, the monitoring stack will automatically scrape metrics from each service's `/actuator/prometheus` endpoint.

## Troubleshooting

### Service won't start
- Check Docker is running: `docker ps`
- Verify docker-compose.monitoring.yaml exists
- Check file path in application.yaml

### Monitoring stack not coming up
- Check logs: `docker compose -f docker-compose.monitoring.yaml logs`
- Increase startup timeout: `monitoring.docker-compose.startup-timeout-seconds: 120`
- Manually test: `docker compose -f docker-compose.monitoring.yaml up -d`

### Port conflicts
- Ensure ports 9090, 3000, 9093, 9100 are available
- Check with: `lsof -i :9090,3000,9093,9100`

## Dependencies

- Docker Desktop or Docker Engine + Docker Compose
- Java 21+
- Spring Boot 3.3.1+
