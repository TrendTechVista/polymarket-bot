package com.polybot.hft.polymarket.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketPosition(
    String proxyWallet,
    String asset,
    String conditionId,
    BigDecimal size,
    BigDecimal avgPrice,
    BigDecimal initialValue,
    BigDecimal currentValue,
    BigDecimal cashPnl,
    BigDecimal realizedPnl,
    BigDecimal curPrice,
    Boolean redeemable,
    Boolean mergeable,
    String title,
    String slug,
    String outcome,
    Integer outcomeIndex,
    String oppositeOutcome,
    String oppositeAsset,
    Boolean negativeRisk
) {
}

