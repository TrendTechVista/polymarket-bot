package com.polymarket.hft.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        Instant acceptedAt,
        String message
) {
}
