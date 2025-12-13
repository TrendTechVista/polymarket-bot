package com.polybot.hft.polymarket.discovery;

import java.math.BigDecimal;

public record DiscoveredMarket(
    String source,
    String id,
    String slug,
    String question,
    String yesTokenId,
    String noTokenId,
    BigDecimal volume,
    Long endEpochMillis
) {
}
