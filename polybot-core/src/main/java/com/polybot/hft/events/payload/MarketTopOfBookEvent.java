package com.polybot.hft.events.payload;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketTopOfBookEvent(
    String assetId,
    BigDecimal bestBid,
    BigDecimal bestBidSize,
    BigDecimal bestAsk,
    BigDecimal bestAskSize,
    BigDecimal lastTradePrice,
    Instant updatedAt,
    Instant lastTradeAt
) {
}
