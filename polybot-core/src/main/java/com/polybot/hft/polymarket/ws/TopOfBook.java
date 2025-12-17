package com.polybot.hft.polymarket.ws;

import java.math.BigDecimal;
import java.time.Instant;

public record TopOfBook(
    BigDecimal bestBid,
    BigDecimal bestAsk,
    BigDecimal bestBidSize,
    BigDecimal bestAskSize,
    BigDecimal lastTradePrice,
    Instant updatedAt,
    Instant lastTradeAt
) {
}
