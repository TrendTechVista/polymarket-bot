package com.polybot.ingestor.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.polybot.hft.metrics")
public class MetricsConfiguration {
}
