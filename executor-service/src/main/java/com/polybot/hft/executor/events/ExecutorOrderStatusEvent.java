package com.polybot.hft.executor.events;

import com.polybot.hft.domain.OrderSide;

import java.math.BigDecimal;

public record ExecutorOrderStatusEvent(
    String orderId,
    String tokenId,
    OrderSide side,
    BigDecimal requestedPrice,
    BigDecimal requestedSize,
    String exchangeStatus,
    BigDecimal matchedSize,
    BigDecimal remainingSize,
    String orderJson,
    String error
) {
}

