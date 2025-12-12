package com.polymarket.hft.polymarket.ws;

import java.math.BigDecimal;
import java.time.Instant;

public record TopOfBook(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal lastTradePrice,
        Instant updatedAt
) {
}

