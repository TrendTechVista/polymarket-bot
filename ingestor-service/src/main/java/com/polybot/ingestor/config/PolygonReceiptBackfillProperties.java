package com.polybot.ingestor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ingestor.polygon.backfill")
public record PolygonReceiptBackfillProperties(
    @NotNull Boolean enabled,
    @NotNull @Min(1) Integer intervalSeconds,
    @NotNull @Min(1) Integer batchSize,
    @NotNull @PositiveOrZero Long requestDelayMillis
) {
  public PolygonReceiptBackfillProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (intervalSeconds == null) {
      intervalSeconds = 30;
    }
    if (batchSize == null) {
      batchSize = 250;
    }
    if (requestDelayMillis == null) {
      requestDelayMillis = 0L;
    }
  }
}

