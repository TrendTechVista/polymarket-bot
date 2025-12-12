package com.polymarket.hft.polymarket.model;

import com.polymarket.hft.domain.OrderSide;

public record SignedOrder(
        String salt,
        String maker,
        String signer,
        String taker,
        String tokenId,
        String makerAmount,
        String takerAmount,
        String expiration,
        String nonce,
        String feeRateBps,
        OrderSide side,
        int signatureType,
        String signature
) {
    public SignedOrder {
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("salt must not be blank");
        }
        if (maker == null || maker.isBlank()) {
            throw new IllegalArgumentException("maker must not be blank");
        }
        if (signer == null || signer.isBlank()) {
            throw new IllegalArgumentException("signer must not be blank");
        }
        if (taker == null || taker.isBlank()) {
            throw new IllegalArgumentException("taker must not be blank");
        }
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId must not be blank");
        }
        if (makerAmount == null || makerAmount.isBlank()) {
            throw new IllegalArgumentException("makerAmount must not be blank");
        }
        if (takerAmount == null || takerAmount.isBlank()) {
            throw new IllegalArgumentException("takerAmount must not be blank");
        }
        if (expiration == null || expiration.isBlank()) {
            throw new IllegalArgumentException("expiration must not be blank");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce must not be blank");
        }
        if (feeRateBps == null || feeRateBps.isBlank()) {
            throw new IllegalArgumentException("feeRateBps must not be blank");
        }
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (signature == null) {
            signature = "";
        }
    }
}
