package com.polybot.hft.polymarket.strategy.config;

import java.math.BigDecimal;

/**
 * Complete-set arbitrage configuration (core target user strategy).
 */
public record CompleteSetConfig(
        double minEdge,
        int maxSkewTicks,
        BigDecimal imbalanceSharesForMaxSkew,
        boolean topUpEnabled,
        long topUpSecondsToEnd,
        BigDecimal topUpMinShares,
        boolean fastTopUpEnabled,
        BigDecimal fastTopUpMinShares,
        long fastTopUpMinSecondsAfterFill,
        long fastTopUpMaxSecondsAfterFill,
        long fastTopUpCooldownMillis,
        double fastTopUpMinEdge
) {
    public static CompleteSetConfig defaults() {
        return new CompleteSetConfig(
                0.01,
                2,
                BigDecimal.valueOf(40),
                true,
                60,
                BigDecimal.TEN,
                true,
                BigDecimal.ONE,
                2,
                120,
                5000,
                0.0
        );
    }
}
