#!/usr/bin/env bash

echo "=========================================="
echo "Stopping All Polybot Services"
echo "=========================================="
echo ""

cd "$(dirname "$0")"

# Function to stop a service
stop_service() {
    local service_name=$1
    local pid_file="logs/${service_name}.pid"

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            echo "Stopping ${service_name} (PID: $pid)..."
            kill $pid
            sleep 2

            # Force kill if still running
            if ps -p $pid > /dev/null 2>&1; then
                echo "  Force stopping ${service_name}..."
                kill -9 $pid
            fi
            echo "  ✓ Stopped"
        else
            echo "${service_name} is not running (stale PID file)"
        fi
        rm -f "$pid_file"
    else
        echo "${service_name}: No PID file found"
    fi
}

# Stop services in reverse order
stop_service "analytics-service"
stop_service "ingestor-service"
stop_service "strategy-service"
stop_service "executor-service"

# Stop infrastructure orchestrator last (it will stop all Docker stacks)
echo ""
echo "Stopping infrastructure-orchestrator-service..."
echo "(This will stop: Redpanda, ClickHouse, Prometheus, Grafana, Alertmanager)"
stop_service "infrastructure-orchestrator-service"

# Wait for Docker cleanup
sleep 2

echo ""
echo "=========================================="
echo "✓ All services stopped"
echo "=========================================="
echo ""
