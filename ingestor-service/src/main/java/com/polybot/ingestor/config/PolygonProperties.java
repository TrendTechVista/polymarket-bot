package com.polybot.ingestor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "ingestor.polygon")
public record PolygonProperties(
    @NotNull Boolean enabled,
    URI rpcUrl,
    @NotNull @Min(1) Integer pollIntervalMillis,
    @NotNull @Min(1) Integer maxReceiptsPerPoll,
    @NotNull @Min(0) Long requestDelayMillis
) {
  public PolygonProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (rpcUrl == null) {
      rpcUrl = URI.create("https://polygon-rpc.com");
    }
    if (pollIntervalMillis == null) {
      pollIntervalMillis = 500;
    }
    if (maxReceiptsPerPoll == null) {
      maxReceiptsPerPoll = 25;
    }
    if (requestDelayMillis == null) {
      requestDelayMillis = 100L;
    }
  }
}

