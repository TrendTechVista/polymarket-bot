package com.polybot.hft.polymarket.strategy.config;

import com.polybot.hft.config.HftProperties;

import java.math.BigDecimal;

/**
 * Bankroll and exposure management configuration.
 */
public record BankrollConfig(
        BigDecimal bankrollUsd,
        HftProperties.BankrollMode bankrollMode,
        long bankrollRefreshMillis,
        boolean dynamicSizingEnabled,
        double dynamicSizingMinMultiplier,
        double dynamicSizingMaxMultiplier,
        double bankrollSmoothingAlpha,
        BigDecimal bankrollMinThreshold,
        double bankrollTradingFraction,
        double maxOrderBankrollFraction,
        double maxTotalBankrollFraction
) {
    public static BankrollConfig defaults() {
        return new BankrollConfig(
                BigDecimal.ZERO,
                HftProperties.BankrollMode.FIXED,
                10000,
                false,
                0.25,
                5.0,
                0.1,
                BigDecimal.ZERO,
                1.0,
                0.0,
                0.0
        );
    }
}
