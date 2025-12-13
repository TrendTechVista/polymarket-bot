package com.polybot.hft.polymarket.api;

public record PolymarketDeriveCredsResponse(
    boolean attempted,
    boolean success,
    String method,
    long nonce,
    String error
) {
}
