package com.polymarket.hft.polymarket.web;

import java.math.BigDecimal;

import com.polymarket.hft.domain.OrderSide;
import com.polymarket.hft.polymarket.model.ClobOrderType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MarketOrderRequest(
        @NotBlank String tokenId,
        @NotNull OrderSide side,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull @DecimalMin("0.0001") @DecimalMax("0.9999") BigDecimal price,
        ClobOrderType orderType,
        BigDecimal tickSize,
        Boolean negRisk,
        Integer feeRateBps,
        Long nonce,
        String taker,
        Boolean deferExec
) {
}

