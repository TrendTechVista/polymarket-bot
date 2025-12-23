package com.polybot.hft.polymarket.strategy.event;

import com.polybot.hft.polymarket.ws.TopOfBook;

import java.math.BigDecimal;

/**
 * Event published when an order lifecycle action occurs (PLACE, CANCEL, FILL).
 */
public record OrderLifecycleEvent(
        String strategy,
        String runId,
        String action,
        String reason,
        String marketSlug,
        String marketType,
        String tokenId,
        String direction,
        Long secondsToEnd,
        BigDecimal tickSize,
        boolean success,
        String error,
        String orderId,
        BigDecimal price,
        BigDecimal size,
        String replacedOrderId,
        BigDecimal replacedPrice,
        BigDecimal replacedSize,
        Long replacedOrderAgeMillis,
        Long orderAgeMillis,
        TopOfBook book,
        String otherTokenId,
        TopOfBook otherBook
) {}
