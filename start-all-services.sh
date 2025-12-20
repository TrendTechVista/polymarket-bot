#!/usr/bin/env bash

set -e

echo "=========================================="
echo "Starting All Polybot Services"
echo "=========================================="
echo ""

# Navigate to project root
cd "$(dirname "$0")"

# Build if needed
if [ ! -f "executor-service/target/executor-service-0.0.1-SNAPSHOT.jar" ] || \
   [ ! -f "strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar" ] || \
   [ ! -f "ingestor-service/target/ingestor-service-0.0.1-SNAPSHOT.jar" ] || \
   [ ! -f "infrastructure-orchestrator-service/target/infrastructure-orchestrator-service-0.0.1-SNAPSHOT.jar" ]; then
    echo "Building all services..."
    mvn clean package -DskipTests
    echo ""
fi

# Create logs directory if not exists
mkdir -p logs

echo "Starting services in background..."
echo ""

# Start infrastructure orchestrator first (it will start all Docker stacks)
echo "1. Starting infrastructure-orchestrator-service (port 8084)..."
echo "   This will start: Redpanda, ClickHouse, Prometheus, Grafana, Alertmanager"
java -jar infrastructure-orchestrator-service/target/infrastructure-orchestrator-service-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=develop \
    > logs/infrastructure-orchestrator-service.log 2>&1 &
echo $! > logs/infrastructure-orchestrator-service.pid
echo "   PID: $(cat logs/infrastructure-orchestrator-service.pid)"

# Wait for infrastructure to be ready
echo "   Waiting for infrastructure stacks to be ready..."
sleep 20

# Start executor service
echo "2. Starting executor-service (port 8080)..."
java -jar executor-service/target/executor-service-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=develop \
    > logs/executor-service.log 2>&1 &
echo $! > logs/executor-service.pid
echo "   PID: $(cat logs/executor-service.pid)"

# Start strategy service
echo "3. Starting strategy-service (port 8081)..."
java -jar strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=develop \
    > logs/strategy-service.log 2>&1 &
echo $! > logs/strategy-service.pid
echo "   PID: $(cat logs/strategy-service.pid)"

# Start ingestor service
echo "4. Starting ingestor-service (port 8083)..."
java -jar ingestor-service/target/ingestor-service-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=develop \
    > logs/ingestor-service.log 2>&1 &
echo $! > logs/ingestor-service.pid
echo "   PID: $(cat logs/ingestor-service.pid)"

# Start analytics service
echo "5. Starting analytics-service (port 8082)..."
java -jar analytics-service/target/analytics-service-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=develop \
    > logs/analytics-service.log 2>&1 &
echo $! > logs/analytics-service.pid
echo "   PID: $(cat logs/analytics-service.pid)"

echo ""
echo "=========================================="
echo "✓ All services started"
echo "=========================================="
echo ""
echo "Service URLs:"
echo "  • Executor:         http://localhost:8080/actuator/health"
echo "  • Strategy:         http://localhost:8081/actuator/health"
echo "  • Analytics:        http://localhost:8082/actuator/health"
echo "  • Ingestor:         http://localhost:8083/actuator/health"
echo "  • Infrastructure:   http://localhost:8084/actuator/health"
echo ""
echo "Analytics Stack:"
echo "  • ClickHouse HTTP:  http://localhost:8123"
echo "  • ClickHouse TCP:   tcp://localhost:9000"
echo "  • Redpanda Kafka:   localhost:9092"
echo "  • Redpanda Admin:   http://localhost:9644"
echo ""
echo "Monitoring Stack:"
echo "  • Grafana:          http://localhost:3000 (admin/polybot123)"
echo "  • Prometheus:       http://localhost:9090"
echo "  • Alertmanager:     http://localhost:9093"
echo ""
echo "Logs:"
echo "  • tail -f logs/executor-service.log"
echo "  • tail -f logs/strategy-service.log"
echo "  • tail -f logs/analytics-service.log"
echo "  • tail -f logs/ingestor-service.log"
echo "  • tail -f logs/infrastructure-orchestrator-service.log"
echo ""
echo "To stop all services:"
echo "  ./stop-all-services.sh"
echo ""
