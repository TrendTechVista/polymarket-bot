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
    @NotNull @Min(0) Long requestDelayMillis,
    /**
     * Concurrent workers for receipt fetching. When 1, behavior is essentially single-threaded.
     */
    @NotNull @Min(1) Integer receiptWorkers,
    /**
     * Cache size for blockNumber->timestamp lookups to avoid repeated eth_getBlockByNumber calls.
     */
    @NotNull @Min(100) Integer blockTimestampCacheSize
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
    if (receiptWorkers == null) {
      receiptWorkers = 1;
    }
    if (blockTimestampCacheSize == null) {
      blockTimestampCacheSize = 50_000;
    }
  }
}
