package com.polybot.hft.polymarket.strategy.config;

/**
 * Timing configuration for the Gabagool strategy.
 */
public record TimingConfig(
        long refreshMillis,
        long minReplaceMillis,
        long minSecondsToEnd,
        long maxSecondsToEnd
) {
    public static TimingConfig defaults() {
        return new TimingConfig(250, 1000, 0, 3600);
    }
}
