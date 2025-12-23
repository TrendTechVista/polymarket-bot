package com.polybot.hft.polymarket.strategy.config;

import java.math.BigDecimal;

/**
 * Taker mode configuration (aggressive order placement).
 */
public record TakerConfig(
        boolean enabled,
        double maxEdge,
        BigDecimal maxSpread
) {
    public static TakerConfig defaults() {
        return new TakerConfig(false, 0.015, BigDecimal.valueOf(0.02));
    }
}
