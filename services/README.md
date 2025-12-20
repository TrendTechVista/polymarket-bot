# Polybot System Services - macOS Edition

Complete service management setup for running Polybot 24/7 with automatic restarts and boot persistence.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│ USER LOGIN / BOOT                                       │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ launchd (macOS Service Manager)                         │
│   ├─ polybot-monitoring (Docker Compose)                │
│   ├─ polybot-analytics (ClickHouse + Redpanda)          │
│   ├─ polybot-executor (Spring Boot - port 8080)         │
│   ├─ polybot-strategy (Spring Boot - port 8081)         │
│   ├─ polybot-ingestor (Spring Boot - port 8083)         │
│   └─ polybot-analytics-api (Spring Boot - port 8082)    │
└─────────────────────────────────────────────────────────┘
                 │
                 ▼
        Auto-restart on crash
        Start on login/boot
        Centralized logging
```

## Quick Start

### 1. Install Services

```bash
cd /Users/antoniostano/programming/polybot
./services/install-services.sh
```

This will:
- ✅ Create launchd plist files
- ✅ Install them to `~/Library/LaunchAgents/`
- ✅ Load and start all services
- ✅ Enable auto-start on login

### 2. Check Service Status

```bash
# View all Polybot services
./services/status.sh

# Or manually:
launchctl list | grep polybot
```

### 3. Start/Stop Services

```bash
# Start all services
./services/start-all.sh

# Stop all services
./services/stop-all.sh

# Restart specific service
launchctl kickstart -k gui/$(id -u)/com.polybot.executor
```

## Service Descriptions

### 1. polybot-monitoring
- **What**: Prometheus, Grafana, Alertmanager, Node Exporter
- **How**: Docker Compose
- **Ports**: 3000 (Grafana), 9090 (Prometheus), 9093 (Alertmanager)
- **Dependencies**: Docker Desktop must be running
- **Restart**: Always (on crash)
- **Logs**: `~/Library/Logs/polybot-monitoring.log`

### 2. polybot-analytics
- **What**: ClickHouse + Redpanda (Kafka)
- **How**: Docker Compose
- **Ports**: 8123 (ClickHouse), 9092 (Kafka)
- **Dependencies**: Docker Desktop
- **Restart**: Always
- **Logs**: `~/Library/Logs/polybot-analytics.log`

### 3. polybot-executor
- **What**: Executor service (owns keys, places orders)
- **How**: Maven Spring Boot
- **Port**: 8080
- **Dependencies**: Java 21, Maven, analytics stack
- **Restart**: On failure (max 3 attempts)
- **Logs**: `~/Library/Logs/polybot-executor.log`

### 4. polybot-strategy
- **What**: Strategy service (trading logic)
- **How**: Maven Spring Boot
- **Port**: 8081
- **Dependencies**: Executor service
- **Restart**: On failure (max 3 attempts)
- **Logs**: `~/Library/Logs/polybot-strategy.log`

### 5. polybot-ingestor
- **What**: Ingestor service (data collection)
- **How**: Maven Spring Boot
- **Port**: 8083
- **Dependencies**: Analytics stack
- **Restart**: On failure (max 3 attempts)
- **Logs**: `~/Library/Logs/polybot-ingestor.log`

### 6. polybot-analytics-api
- **What**: Analytics API service (query interface)
- **How**: Maven Spring Boot
- **Port**: 8082
- **Dependencies**: ClickHouse
- **Restart**: On failure (max 3 attempts)
- **Logs**: `~/Library/Logs/polybot-analytics-api.log`

## Service Dependencies

```
Docker Desktop (must be running manually)
    │
    ├──► polybot-monitoring (starts automatically)
    │
    └──► polybot-analytics
             │
             ├──► polybot-executor
             │        │
             │        └──► polybot-strategy
             │
             ├──► polybot-ingestor
             │
             └──► polybot-analytics-api
```

**Startup Order** (handled automatically):
1. Docker Desktop (manual)
2. Monitoring + Analytics (Docker Compose)
3. Executor → Strategy
4. Ingestor
5. Analytics API

## Configuration

### Service Restart Policies

**Docker services** (monitoring, analytics):
- `Restart: always` - Restarts immediately on crash
- Survives Docker Desktop restart

**Spring Boot services** (executor, strategy, etc.):
- `SuccessfulExit: false` - Restart on abnormal exit only
- `Crashed: true` - Restart on crash
- `ThrottleInterval: 60` - Wait 60s before restart

### Modify Restart Behavior

Edit the `.plist` file:
```bash
nano ~/Library/LaunchAgents/com.polybot.executor.plist
```

Change `ThrottleInterval` for faster/slower restarts:
```xml
<key>ThrottleInterval</key>
<integer>30</integer>  <!-- Restart after 30 seconds -->
```

Reload after changes:
```bash
launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist
launchctl load ~/Library/LaunchAgents/com.polybot.executor.plist
```

## Viewing Logs

### Real-time Log Monitoring

```bash
# All services (consolidated)
tail -f ~/Library/Logs/polybot-*.log

# Specific service
tail -f ~/Library/Logs/polybot-executor.log

# With filtering
tail -f ~/Library/Logs/polybot-strategy.log | grep ERROR

# Last 100 lines
tail -n 100 ~/Library/Logs/polybot-executor.log
```

### Log Rotation

macOS automatically rotates logs in `~/Library/Logs/`. To manually configure:

```bash
# Create log rotation config
sudo nano /etc/newsyslog.d/polybot.conf
```

Add:
```
# Polybot logs - rotate daily, keep 7 days
/Users/antoniostano/Library/Logs/polybot-*.log  644  7  *  @T00  J
```

## Troubleshooting

### Service Won't Start

```bash
# Check service status
launchctl list | grep polybot

# View error output
cat ~/Library/Logs/polybot-executor.log

# Check if port is already in use
lsof -i :8080

# Manually test command
cd /Users/antoniostano/programming/polybot
mvn -pl executor-service spring-boot:run
```

### Service Keeps Crashing

```bash
# Check crash logs
tail -n 200 ~/Library/Logs/polybot-executor.log

# Disable auto-restart temporarily
launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist

# Debug manually
cd /Users/antoniostano/programming/polybot
mvn -pl executor-service spring-boot:run -Dspring-boot.run.profiles=develop
```

### Docker Services Won't Start

```bash
# Ensure Docker Desktop is running
docker info

# Check container status
docker ps -a | grep polybot

# Manual start
cd /Users/antoniostano/programming/polybot
docker compose -f docker-compose.monitoring.yaml up -d
```

### Service Not Auto-Starting on Login

```bash
# Verify service is loaded
launchctl list | grep polybot

# Check plist syntax
plutil -lint ~/Library/LaunchAgents/com.polybot.executor.plist

# Reload service
launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist
launchctl load ~/Library/LaunchAgents/com.polybot.executor.plist
```

## Advanced Management

### Manual Service Control

```bash
# Load service (enable auto-start)
launchctl load ~/Library/LaunchAgents/com.polybot.executor.plist

# Unload service (disable auto-start)
launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist

# Start service now
launchctl start com.polybot.executor

# Stop service now
launchctl stop com.polybot.executor

# Restart service (kill and restart)
launchctl kickstart -k gui/$(id -u)/com.polybot.executor

# Remove service completely
launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist
rm ~/Library/LaunchAgents/com.polybot.executor.plist
```

### Check Service Health

```bash
# Is service running?
launchctl list | grep polybot

# PID and status
launchctl list com.polybot.executor

# Detailed status
launchctl print gui/$(id -u)/com.polybot.executor
```

### Environment Variables

Services run with a minimal environment. To add env vars:

Edit plist file:
```xml
<key>EnvironmentVariables</key>
<dict>
    <key>JAVA_HOME</key>
    <string>/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home</string>
    <key>SPRING_PROFILES_ACTIVE</key>
    <string>develop</string>
</dict>
```

## Security Considerations

### Production Deployment

1. **Separate User Account**
   ```bash
   # Create dedicated polybot user
   sudo dscl . -create /Users/polybot
   sudo dscl . -create /Users/polybot UserShell /bin/bash

   # Move services to system daemon
   sudo mv ~/Library/LaunchAgents/com.polybot.* /Library/LaunchDaemons/
   ```

2. **Restrict Permissions**
   ```bash
   # Service files
   chmod 644 ~/Library/LaunchAgents/com.polybot.*.plist

   # Log files
   chmod 640 ~/Library/Logs/polybot-*.log
   ```

3. **Secrets Management**
   - Use environment variables for API keys
   - Store credentials in macOS Keychain
   - Never commit `.env` files with secrets

### Monitoring Service Health

Use the monitoring stack to watch the services themselves:

```bash
# Check if services are responding
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health

# View in Grafana
open http://localhost:3000
```

## Backup and Recovery

### Backup Service Configurations

```bash
# Backup all plist files
cp ~/Library/LaunchAgents/com.polybot.* ~/polybot-service-backup/

# Backup logs
tar -czf polybot-logs-$(date +%Y%m%d).tar.gz ~/Library/Logs/polybot-*.log
```

### Restore After System Reinstall

```bash
# Restore plist files
cp ~/polybot-service-backup/*.plist ~/Library/LaunchAgents/

# Load all services
cd /Users/antoniostano/programming/polybot
./services/install-services.sh
```

## Performance Tuning

### Java Service Optimization

Edit plist to add JVM options:
```xml
<key>ProgramArguments</key>
<array>
    <string>/opt/homebrew/bin/mvn</string>
    <string>-pl</string>
    <string>executor-service</string>
    <string>spring-boot:run</string>
    <string>-Dspring-boot.run.jvmArguments=-Xmx512m -XX:+UseG1GC</string>
</array>
```

### Docker Resource Limits

Edit `docker-compose.monitoring.yaml`:
```yaml
services:
  prometheus:
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '1.0'
```

## Uninstall

To completely remove all services:

```bash
cd /Users/antoniostano/programming/polybot
./services/uninstall-services.sh
```

Or manually:
```bash
# Stop and unload all services
for service in monitoring analytics executor strategy ingestor analytics-api; do
    launchctl unload ~/Library/LaunchAgents/com.polybot.$service.plist 2>/dev/null
    rm ~/Library/LaunchAgents/com.polybot.$service.plist
done

# Remove logs
rm ~/Library/Logs/polybot-*.log
```

## Alternative: Docker-Only Approach

If you prefer running everything in Docker (cross-platform):

```bash
# See: docker-compose.full-stack.yaml
docker compose -f docker-compose.full-stack.yaml up -d
```

This approach:
- ✅ Works on macOS, Linux, Windows
- ✅ Simpler deployment
- ✅ Better isolation
- ❌ Slightly higher resource usage
- ❌ Slower Spring Boot hot-reload during development

## Quick Reference

| Task | Command |
|------|---------|
| Install services | `./services/install-services.sh` |
| Start all | `./services/start-all.sh` |
| Stop all | `./services/stop-all.sh` |
| Status check | `./services/status.sh` |
| View logs | `tail -f ~/Library/Logs/polybot-*.log` |
| Restart service | `launchctl kickstart -k gui/$(id -u)/com.polybot.executor` |
| Disable service | `launchctl unload ~/Library/LaunchAgents/com.polybot.executor.plist` |
| Enable service | `launchctl load ~/Library/LaunchAgents/com.polybot.executor.plist` |

## Getting Help

- **launchd docs**: `man launchd`, `man launchctl`
- **Service logs**: `~/Library/Logs/polybot-*.log`
- **System logs**: Console.app → User Reports
- **Docker logs**: `docker logs <container-name>`
