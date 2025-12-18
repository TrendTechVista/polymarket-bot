package com.polybot.hft.events;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="hft.events")
public record HftEventsProperties(
    @NotNull Boolean enabled,
    String topic,
    @NotNull @PositiveOrZero Long marketWsTobMinIntervalMillis,
    /**
     * When enabled, republish the persisted WS TOB cache on startup.
     *
     * This helps maintain ASOF join coverage across restarts (at the cost of using older book snapshots until fresh
     * WS updates arrive).
     */
    @NotNull Boolean marketWsCachePublishOnStart
) {
  public HftEventsProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (topic == null || topic.isBlank()) {
      topic = "polybot.events";
    }
    if (marketWsTobMinIntervalMillis == null) {
      marketWsTobMinIntervalMillis = 250L;
    }
    if (marketWsCachePublishOnStart == null) {
      marketWsCachePublishOnStart = false;
    }
  }
}
