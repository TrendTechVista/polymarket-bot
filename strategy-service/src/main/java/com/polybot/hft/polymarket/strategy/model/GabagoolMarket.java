package com.polybot.hft.polymarket.strategy.model;

import java.time.Instant;

/**
 * Represents an active Up/Down market being tracked by the strategy.
 */
public record GabagoolMarket(
        String slug,
        String upTokenId,
        String downTokenId,
        Instant endTime,
        String marketType  // "updown-15m" or "up-or-down"
) {}
