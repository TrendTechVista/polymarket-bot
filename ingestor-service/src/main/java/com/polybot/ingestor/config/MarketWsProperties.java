package com.polybot.ingestor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ingestor.market-ws")
public record MarketWsProperties(
    @NotNull Boolean enabled,
    @NotNull @Min(1) Integer discoveryIntervalSeconds,
    @NotNull @Min(1) Integer lookback15mMinutes,
    @NotNull @Min(0) Integer lookahead15mMinutes,
    @NotNull @Min(0) Integer lookback1hHours,
    @NotNull @Min(0) Integer lookahead1hHours,
    @NotNull @Min(0) Long requestDelayMillis
) {
  public MarketWsProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (discoveryIntervalSeconds == null) {
      discoveryIntervalSeconds = 30;
    }
    if (lookback15mMinutes == null) {
      lookback15mMinutes = 30;
    }
    if (lookahead15mMinutes == null) {
      lookahead15mMinutes = 15;
    }
    if (lookback1hHours == null) {
      lookback1hHours = 2;
    }
    if (lookahead1hHours == null) {
      lookahead1hHours = 1;
    }
    if (requestDelayMillis == null) {
      requestDelayMillis = 100L;
    }
  }
}

