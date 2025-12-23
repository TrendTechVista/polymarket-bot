package com.polybot.hft.polymarket.strategy.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tracks per-market inventory for complete-set coordination.
 * Used to detect imbalance between UP and DOWN positions.
 */
public record MarketInventory(
        BigDecimal upShares,
        BigDecimal downShares,
        Instant lastUpFillAt,
        Instant lastDownFillAt,
        BigDecimal lastUpFillPrice,
        BigDecimal lastDownFillPrice,
        Instant lastTopUpAt
) {
    public MarketInventory {
        if (upShares == null) upShares = BigDecimal.ZERO;
        if (downShares == null) downShares = BigDecimal.ZERO;
    }

    public static MarketInventory empty() {
        return new MarketInventory(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null);
    }

    public BigDecimal imbalance() {
        return upShares.subtract(downShares);
    }

    public BigDecimal totalShares() {
        return upShares.add(downShares);
    }

    public MarketInventory addUp(BigDecimal shares, Instant fillAt, BigDecimal fillPrice) {
        return new MarketInventory(
                upShares.add(shares),
                downShares,
                fillAt,
                lastDownFillAt,
                fillPrice,
                lastDownFillPrice,
                lastTopUpAt
        );
    }

    public MarketInventory addDown(BigDecimal shares, Instant fillAt, BigDecimal fillPrice) {
        return new MarketInventory(
                upShares,
                downShares.add(shares),
                lastUpFillAt,
                fillAt,
                lastUpFillPrice,
                fillPrice,
                lastTopUpAt
        );
    }

    public MarketInventory markTopUp(Instant at) {
        return new MarketInventory(
                upShares,
                downShares,
                lastUpFillAt,
                lastDownFillAt,
                lastUpFillPrice,
                lastDownFillPrice,
                at
        );
    }
}
