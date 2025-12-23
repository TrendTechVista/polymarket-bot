package com.polybot.hft.polymarket.strategy.config;

import java.math.BigDecimal;

/**
 * Order sizing configuration for the Gabagool strategy.
 */
public record SizingConfig(
        BigDecimal quoteSize,
        double quoteSizeBankrollFraction,
        int improveTicks
) {
    public static SizingConfig defaults() {
        return new SizingConfig(BigDecimal.TEN, 0.0, 1);
    }
}
