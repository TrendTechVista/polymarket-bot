package com.polybot.hft.executor.settlement;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "executor.settlement")
public record ExecutorSettlementProperties(
    /**
     * When enabled, executor will periodically attempt to merge complete sets and redeem resolved positions.
     */
    @NotNull Boolean enabled,
    /**
     * When true, only logs planned actions and does NOT send any on-chain transactions.
     */
    @NotNull Boolean dryRun,
    /**
     * Poll interval for auto settlement.
     */
    @NotNull @Min(1_000) Long pollIntervalMillis,
    /**
     * Minimum merge amount (in shares) to avoid sending tiny merge transactions.
     */
    @NotNull @PositiveOrZero BigDecimal minMergeShares,
    /**
     * Maximum retry attempts for failed settlement actions (redeem/merge txs).
     */
    @NotNull @PositiveOrZero Integer maxRetries,
    /**
     * When true, only merge complete sets for markets ending within mergeSecondsBeforeEnd.
     * When false, merge complete sets immediately when detected.
     */
    @NotNull Boolean mergeOnlyNearEnd,
    /**
     * Only merge if market ends within this many seconds (when mergeOnlyNearEnd = true).
     */
    @NotNull @PositiveOrZero Long mergeSecondsBeforeEnd
) {
  public ExecutorSettlementProperties {
    if (enabled == null) {
      enabled = false;
    }
    if (dryRun == null) {
      dryRun = true;
    }
    if (pollIntervalMillis == null) {
      pollIntervalMillis = 30_000L;
    }
    if (minMergeShares == null) {
      minMergeShares = BigDecimal.ONE;
    }
    if (maxRetries == null) {
      maxRetries = 3;
    }
    if (mergeOnlyNearEnd == null) {
      mergeOnlyNearEnd = false;
    }
    if (mergeSecondsBeforeEnd == null) {
      mergeSecondsBeforeEnd = 300L; // 5 minutes
    }
  }
}

