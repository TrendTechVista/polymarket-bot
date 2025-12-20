package com.polybot.infrastructure.orchestrator.health;

import com.polybot.infrastructure.orchestrator.service.DockerComposeLifecycleManager;
import com.polybot.infrastructure.orchestrator.service.DockerComposeLifecycleManager.InfrastructureStatus;
import com.polybot.infrastructure.orchestrator.service.DockerComposeLifecycleManager.StackStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class InfrastructureHealthIndicator implements HealthIndicator {

    private final DockerComposeLifecycleManager lifecycleManager;

    @Override
    public Health health() {
        InfrastructureStatus status = lifecycleManager.getInfrastructureStatus();

        boolean isHealthy = status.managed() && "HEALTHY".equals(status.overallHealth());

        Health.Builder builder = isHealthy ? Health.up() : Health.down();

        builder
            .withDetail("managed", status.managed())
            .withDetail("overallHealth", status.overallHealth());

        // Add details for each stack
        for (StackStatus stack : status.stacks()) {
            builder.withDetail(stack.name() + "_stack", Map.of(
                "runningServices", stack.runningServices(),
                "expectedServices", stack.expectedServices(),
                "health", stack.healthStatus()
            ));
        }

        return builder.build();
    }
}
