package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.model.SignedOrder;

public record OrderSubmissionResult(
        HftProperties.TradingMode mode,
        SignedOrder signedOrder,
        JsonNode clobResponse
) {
}

