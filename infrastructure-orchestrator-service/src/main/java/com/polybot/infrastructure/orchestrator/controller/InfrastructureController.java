package com.polybot.infrastructure.orchestrator.controller;

import com.polybot.infrastructure.orchestrator.service.DockerComposeLifecycleManager;
import com.polybot.infrastructure.orchestrator.service.DockerComposeLifecycleManager.InfrastructureStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/infrastructure")
@RequiredArgsConstructor
public class InfrastructureController {

    private final DockerComposeLifecycleManager lifecycleManager;

    @GetMapping("/status")
    public ResponseEntity<InfrastructureStatus> getStatus() {
        log.debug("GET /api/infrastructure/status");
        return ResponseEntity.ok(lifecycleManager.getInfrastructureStatus());
    }

    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restart() {
        log.info("POST /api/infrastructure/restart - Manual restart requested");
        try {
            lifecycleManager.stopInfrastructureStacks();
            Thread.sleep(2000); // Brief pause
            lifecycleManager.startInfrastructureStacks();
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Infrastructure stacks restarted successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to restart infrastructure stacks", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", "Failed to restart: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        InfrastructureStatus status = lifecycleManager.getInfrastructureStatus();
        boolean isHealthy = "HEALTHY".equals(status.overallHealth());

        return ResponseEntity
            .status(isHealthy ? 200 : 503)
            .body(Map.of(
                "status", isHealthy ? "UP" : "DOWN",
                "overallHealth", status.overallHealth(),
                "managed", status.managed(),
                "stacks", status.stacks()
            ));
    }

    @GetMapping("/links")
    public ResponseEntity<Map<String, Object>> getInfrastructureLinks() {
        return ResponseEntity.ok(Map.of(
            "analytics", Map.of(
                "clickhouse_http", "http://localhost:8123",
                "clickhouse_native", "tcp://localhost:9000",
                "redpanda_kafka", "localhost:9092",
                "redpanda_admin", "http://localhost:9644"
            ),
            "monitoring", Map.of(
                "grafana", "http://localhost:3000 (admin/changeme)",
                "prometheus", "http://localhost:9090",
                "alertmanager", "http://localhost:9093"
            ),
            "this_service", "http://localhost:8084/actuator/health"
        ));
    }
}
