package com.polybot.infrastructure.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "infrastructure")
public class InfrastructureProperties {
    private List<DockerComposeStack> stacks;
    private int startupTimeoutSeconds = 60;
    private int healthCheckIntervalSeconds = 5;

    @Data
    public static class DockerComposeStack {
        private String name;
        private String filePath;
        private String projectName;
        private int expectedServices = 1;
        private int startupOrder = 0; // Lower starts first
    }
}
