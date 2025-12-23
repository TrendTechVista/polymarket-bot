package com.polybot.hft.polymarket.strategy.service;

import com.polybot.hft.polymarket.data.PolymarketPosition;
import com.polybot.hft.polymarket.strategy.model.GabagoolMarket;
import com.polybot.hft.polymarket.strategy.model.MarketInventory;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks positions and per-market inventory for complete-set coordination.
 */
@Slf4j
public class PositionTracker {

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final ExecutorApiClient executorApi;
    private final Clock clock;

    private final AtomicReference<PositionsCache> positionsCache = new AtomicReference<>(
            new PositionsCache(Instant.EPOCH, Map.of(), Map.of(), BigDecimal.ZERO)
    );

    private final Map<String, MarketInventory> inventoryByMarket = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> fillsSinceRefresh = new ConcurrentHashMap<>();
    private final AtomicReference<BigDecimal> fillsNotionalSinceRefresh = new AtomicReference<>(BigDecimal.ZERO);

    public PositionTracker(ExecutorApiClient executorApi, Clock clock) {
        this.executorApi = executorApi;
        this.clock = clock;
    }

    /**
     * Refresh positions cache if stale.
     */
    public void refreshIfStale() {
        Instant now = clock.instant();
        PositionsCache cached = positionsCache.get();
        if (cached != null && cached.fetchedAt() != null
                && Duration.between(cached.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
            return;
        }

        try {
            PositionsCache next = fetchPositions(now);
            positionsCache.set(next);
            resetFillsSinceRefresh();
        } catch (Exception e) {
            PositionsCache existing = positionsCache.get();
            positionsCache.set(new PositionsCache(now,
                    existing.sharesByTokenId() != null ? existing.sharesByTokenId() : Map.of(),
                    existing.openNotionalByTokenId() != null ? existing.openNotionalByTokenId() : Map.of(),
                    existing.openNotionalUsd() != null ? existing.openNotionalUsd() : BigDecimal.ZERO
            ));
            log.debug("positions refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Sync inventory from positions for active markets.
     */
    public void syncInventory(List<GabagoolMarket> markets) {
        PositionsCache cache = positionsCache.get();
        if (cache == null || cache.sharesByTokenId() == null || cache.sharesByTokenId().isEmpty()) return;
        if (markets == null || markets.isEmpty()) return;

        Map<String, BigDecimal> shares = cache.sharesByTokenId();

        for (GabagoolMarket market : markets) {
            if (market == null || market.slug() == null || market.slug().isBlank()) continue;
            if (market.upTokenId() == null || market.downTokenId() == null) continue;

            BigDecimal upShares = shares.getOrDefault(market.upTokenId(), BigDecimal.ZERO);
            BigDecimal downShares = shares.getOrDefault(market.downTokenId(), BigDecimal.ZERO);

            inventoryByMarket.compute(market.slug(), (k, prev) -> {
                MarketInventory current = prev == null ? MarketInventory.empty() : prev;
                return new MarketInventory(
                        upShares, downShares,
                        current.lastUpFillAt(), current.lastDownFillAt(),
                        current.lastUpFillPrice(), current.lastDownFillPrice(),
                        current.lastTopUpAt()
                );
            });
        }
    }

    /**
     * Get inventory for a market.
     */
    public MarketInventory getInventory(String marketSlug) {
        return inventoryByMarket.computeIfAbsent(marketSlug, k -> MarketInventory.empty());
    }

    /**
     * Update inventory after a fill.
     */
    public void recordFill(String marketSlug, boolean isUp, BigDecimal shares, BigDecimal price) {
        Instant now = clock.instant();
        inventoryByMarket.compute(marketSlug, (k, inv) -> {
            MarketInventory current = inv == null ? MarketInventory.empty() : inv;
            return isUp
                    ? current.addUp(shares, now, price)
                    : current.addDown(shares, now, price);
        });
    }

    /**
     * Mark top-up attempt for cooldown tracking.
     */
    public void markTopUp(String marketSlug) {
        Instant now = clock.instant();
        inventoryByMarket.compute(marketSlug, (k, inv) -> {
            MarketInventory current = inv == null ? MarketInventory.empty() : inv;
            return current.markTopUp(now);
        });
    }

    /**
     * Get all inventories for exposure calculation.
     */
    public Map<String, MarketInventory> getAllInventories() {
        return Map.copyOf(inventoryByMarket);
    }

    private PositionsCache fetchPositions(Instant now) {
        int limit = 200;
        int maxOffset = 2_000;

        Map<String, BigDecimal> sharesByTokenId = new HashMap<>();
        Map<String, BigDecimal> notionalByTokenId = new HashMap<>();
        BigDecimal totalNotional = BigDecimal.ZERO;

        for (int offset = 0; offset <= maxOffset; offset += limit) {
            PolymarketPosition[] page = executorApi.getPositions(limit, offset);
            if (page == null || page.length == 0) break;

            for (PolymarketPosition p : page) {
                if (p == null || Boolean.TRUE.equals(p.redeemable())) continue;

                if (p.asset() != null && !p.asset().isBlank() && p.size() != null) {
                    BigDecimal size = p.size().abs();
                    sharesByTokenId.merge(p.asset(), size, BigDecimal::add);
                }

                BigDecimal initialValue = p.initialValue();
                if (initialValue == null) continue;
                initialValue = initialValue.abs();
                totalNotional = totalNotional.add(initialValue);
                if (p.asset() != null && !p.asset().isBlank()) {
                    notionalByTokenId.merge(p.asset(), initialValue, BigDecimal::add);
                }
            }

            if (page.length < limit) break;
        }

        return new PositionsCache(now, sharesByTokenId, notionalByTokenId, totalNotional);
    }

    private void resetFillsSinceRefresh() {
        fillsSinceRefresh.clear();
        fillsNotionalSinceRefresh.set(BigDecimal.ZERO);
    }

    public record PositionsCache(
            Instant fetchedAt,
            Map<String, BigDecimal> sharesByTokenId,
            Map<String, BigDecimal> openNotionalByTokenId,
            BigDecimal openNotionalUsd
    ) {}
}
