package com.polybot.hft.polymarket.api;

import java.math.BigDecimal;

/**
 * Executor-computed bankroll snapshot used for dynamic sizing.
 * <p>
 * This intentionally uses simple fields (BigDecimal + primitive) so it can be safely consumed by
 * the strategy-service via HTTP without needing extra schema tooling.
 */
public record PolymarketBankrollResponse(
    String mode,
    String makerAddress,
    BigDecimal usdcBalance,
    BigDecimal positionsCurrentValueUsd,
    BigDecimal positionsInitialValueUsd,
    BigDecimal totalEquityUsd,
    int positionsCount,
    int redeemablePositionsCount,
    int mergeablePositionsCount,
    long asOfMillis
) {
}

