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
     * Optional periodic "snapshot republish" interval for market WS top-of-book.
     *
     * Why: WS only emits when something changes. For decision-time ASOF joins (and to measure freshness), we want a
     * near-continuous TOB stream even when the book is stable. When > 0, the WS client periodically republishes the
     * latest cached TOB for subscribed assets with {@code updatedAt=now}.
     *
     * Set to 0 to disable.
     */
    @NotNull @PositiveOrZero Long marketWsSnapshotPublishMillis,
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
    if (marketWsSnapshotPublishMillis == null) {
      marketWsSnapshotPublishMillis = 0L;
    }
    if (marketWsCachePublishOnStart == null) {
      marketWsCachePublishOnStart = false;
    }
  }
}
