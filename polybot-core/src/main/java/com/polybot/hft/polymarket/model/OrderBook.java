package com.polybot.hft.polymarket.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderBook(
        String market,
        @JsonProperty("asset_id") @JsonAlias("assetId") String assetId,
        String timestamp,
        String hash,
        @JsonAlias("buys") List<OrderBookLevel> bids,
        @JsonAlias("sells") List<OrderBookLevel> asks
) {
}

