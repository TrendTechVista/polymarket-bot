package com.polybot.hft.polymarket.strategy.service;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.api.PolymarketBankrollResponse;
import com.polybot.hft.polymarket.strategy.config.GabagoolConfig;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import com.polybot.hft.strategy.metrics.StrategyMetricsService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages bankroll tracking with EMA smoothing and circuit breaker logic.
 */
@Slf4j
public class BankrollService {

    private static final Duration CACHE_STALE_GRACE = Duration.ofSeconds(60);

    private final ExecutorApiClient executorApi;
    private final StrategyMetricsService metricsService;
    private final Clock clock;

    private final AtomicReference<BankrollSnapshot> cache = new AtomicReference<>(
            new BankrollSnapshot(Instant.EPOCH, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    );

    public BankrollService(ExecutorApiClient executorApi, StrategyMetricsService metricsService, Clock clock) {
        this.executorApi = executorApi;
        this.metricsService = metricsService;
        this.clock = clock;
    }

    /**
     * Refresh bankroll cache if stale.
     */
    public void refreshIfStale(GabagoolConfig cfg) {
        if (cfg == null) return;
        Instant now = clock.instant();

        // FIXED mode: just publish configured bankroll for metrics
        if (cfg.bankrollMode() == HftProperties.BankrollMode.FIXED) {
            if (cfg.bankrollUsd() != null) {
                metricsService.updateBankroll(cfg.bankrollUsd());
            }
            return;
        }

        long refreshMillis = Math.max(1_000L, cfg.bankrollRefreshMillis());
        BankrollSnapshot cached = cache.get();
        if (cached != null && cached.fetchedAt() != null
                && Duration.between(cached.fetchedAt(), now).toMillis() < refreshMillis) {
            BigDecimal effective = resolveEffective(cfg);
            if (effective != null) metricsService.updateBankroll(effective);
            return;
        }

        try {
            PolymarketBankrollResponse resp = executorApi.getBankroll();
            BigDecimal usdc = resp != null && resp.usdcBalance() != null ? resp.usdcBalance() : BigDecimal.ZERO;
            BigDecimal equity = resp != null && resp.totalEquityUsd() != null ? resp.totalEquityUsd() : usdc;

            // EMA smoothing
            double alpha = Math.max(0.01, Math.min(1.0, cfg.bankrollSmoothingAlpha()));
            BigDecimal alphaBD = BigDecimal.valueOf(alpha);
            BigDecimal oneMinusAlpha = BigDecimal.ONE.subtract(alphaBD);

            BigDecimal prevSmoothedUsdc = cached.smoothedUsdcBalance() != null ? cached.smoothedUsdcBalance() : usdc;
            BigDecimal prevSmoothedEquity = cached.smoothedEquityUsd() != null ? cached.smoothedEquityUsd() : equity;

            BigDecimal smoothedUsdc = usdc.multiply(alphaBD).add(prevSmoothedUsdc.multiply(oneMinusAlpha));
            BigDecimal smoothedEquity = equity.multiply(alphaBD).add(prevSmoothedEquity.multiply(oneMinusAlpha));

            cache.set(new BankrollSnapshot(now, usdc, equity, smoothedUsdc, smoothedEquity));

            BigDecimal effective = resolveEffective(cfg);
            if (effective != null) metricsService.updateBankroll(effective);

            log.debug("bankroll refreshed: usdc={} equity={} smoothedUsdc={} smoothedEquity={}",
                    usdc, equity, smoothedUsdc, smoothedEquity);
        } catch (Exception e) {
            BankrollSnapshot existing = cache.get();
            cache.set(new BankrollSnapshot(now,
                    existing.usdcBalance() != null ? existing.usdcBalance() : BigDecimal.ZERO,
                    existing.totalEquityUsd() != null ? existing.totalEquityUsd() : BigDecimal.ZERO,
                    existing.smoothedUsdcBalance() != null ? existing.smoothedUsdcBalance() : BigDecimal.ZERO,
                    existing.smoothedEquityUsd() != null ? existing.smoothedEquityUsd() : BigDecimal.ZERO
            ));
            log.debug("bankroll refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Get effective bankroll based on config mode.
     */
    public BigDecimal resolveEffective(GabagoolConfig cfg) {
        if (cfg == null) return null;

        if (cfg.bankrollMode() == HftProperties.BankrollMode.FIXED) {
            return applyTradingFraction(cfg.bankrollUsd(), cfg);
        }

        BankrollSnapshot snap = cache.get();
        if (snap == null || snap.fetchedAt() == null) {
            return applyTradingFraction(cfg.bankrollUsd(), cfg);
        }

        Duration age = Duration.between(snap.fetchedAt(), clock.instant());
        if (age.compareTo(CACHE_STALE_GRACE) > 0) {
            return applyTradingFraction(cfg.bankrollUsd(), cfg);
        }

        BigDecimal candidate = cfg.bankrollMode() == HftProperties.BankrollMode.AUTO_CASH
                ? snap.smoothedUsdcBalance()
                : snap.smoothedEquityUsd();

        if (candidate == null || candidate.compareTo(BigDecimal.ZERO) <= 0) {
            return applyTradingFraction(cfg.bankrollUsd(), cfg);
        }
        return applyTradingFraction(candidate, cfg);
    }

    /**
     * Check if bankroll is below threshold (circuit breaker).
     */
    public boolean isBelowThreshold(GabagoolConfig cfg) {
        if (cfg == null) return false;
        BigDecimal threshold = cfg.bankrollMinThreshold();
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) return false;

        BigDecimal effective = resolveEffective(cfg);
        if (effective == null) return false;

        return effective.compareTo(threshold) < 0;
    }

    /**
     * Get dynamic sizing multiplier based on actual vs reference bankroll.
     */
    public BigDecimal getDynamicSizingMultiplier(GabagoolConfig cfg) {
        if (cfg == null || !cfg.dynamicSizingEnabled()) return BigDecimal.ONE;

        BigDecimal reference = cfg.bankrollUsd();
        if (reference == null || reference.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ONE;

        BigDecimal actual = resolveEffective(cfg);
        if (actual == null || actual.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ONE;

        BigDecimal ratio = actual.divide(reference, 8, java.math.RoundingMode.HALF_UP);
        BigDecimal min = BigDecimal.valueOf(Math.max(0.0, cfg.dynamicSizingMinMultiplier()));
        BigDecimal max = BigDecimal.valueOf(Math.max(min.doubleValue(), cfg.dynamicSizingMaxMultiplier()));

        if (ratio.compareTo(min) < 0) return min;
        if (ratio.compareTo(max) > 0) return max;
        return ratio;
    }

    private BigDecimal applyTradingFraction(BigDecimal bankroll, GabagoolConfig cfg) {
        if (bankroll == null || cfg == null) return bankroll;
        double fraction = Math.max(0.0, Math.min(1.0, cfg.bankrollTradingFraction()));
        if (fraction >= 1.0) return bankroll;
        return bankroll.multiply(BigDecimal.valueOf(fraction));
    }

    public record BankrollSnapshot(
            Instant fetchedAt,
            BigDecimal usdcBalance,
            BigDecimal totalEquityUsd,
            BigDecimal smoothedUsdcBalance,
            BigDecimal smoothedEquityUsd
    ) {}
}
