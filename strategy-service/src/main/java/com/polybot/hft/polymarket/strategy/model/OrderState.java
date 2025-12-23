package com.polybot.hft.polymarket.strategy.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * State of an open order being tracked by the strategy.
 */
public record OrderState(
        String orderId,
        GabagoolMarket market,
        String tokenId,
        Direction direction,
        BigDecimal price,
        BigDecimal size,
        Instant placedAt,
        BigDecimal matchedSize,
        Instant lastStatusCheckAt,
        long secondsToEndAtEntry
) {}
