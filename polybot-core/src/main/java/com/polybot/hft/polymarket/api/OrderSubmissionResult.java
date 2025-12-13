package com.polybot.hft.polymarket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.model.SignedOrder;

public record OrderSubmissionResult(
    HftProperties.TradingMode mode,
    SignedOrder signedOrder,
    JsonNode clobResponse
) {
}
