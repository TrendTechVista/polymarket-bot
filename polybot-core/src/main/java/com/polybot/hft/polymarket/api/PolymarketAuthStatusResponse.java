package com.polybot.hft.polymarket.api;

public record PolymarketAuthStatusResponse(
    String mode,
    String[] activeProfiles,
    boolean signerConfigured,
    String signerAddress,
    boolean apiCredsConfigured,
    boolean autoCreateOrDeriveEnabled,
    long configuredNonce,
    String clobRestUrl,
    boolean envPrivateKeyPresent,
    Integer envPrivateKeyLength,
    boolean resolvedPrivateKeyPresent,
    Integer resolvedPrivateKeyLength,
    String resolvedAutoDeriveProperty
) {
}
