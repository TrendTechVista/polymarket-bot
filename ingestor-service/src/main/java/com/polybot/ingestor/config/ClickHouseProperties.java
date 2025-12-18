package com.polybot.ingestor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "ingestor.clickhouse")
public record ClickHouseProperties(
    URI url,
    String database,
    String user,
    String password,
    @NotNull @Min(1) Integer timeoutSeconds
) {
  public ClickHouseProperties {
    if (url == null) {
      url = URI.create("http://localhost:8123");
    }
    if (database == null || database.isBlank()) {
      database = "polybot";
    }
    if (user == null || user.isBlank()) {
      user = "default";
    }
    if (password == null) {
      password = "";
    }
    if (timeoutSeconds == null) {
      timeoutSeconds = 10;
    }
  }
}

