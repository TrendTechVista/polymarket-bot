package com.polybot.hft.polymarket.api;

public record PolymarketAccountResponse(
    String mode,
    String signerAddress,
    String makerAddress,
    String funderAddress
) {
}

