package com.polybot.hft.strategy.metrics;

import com.polybot.hft.metrics.PolybotMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics service for strategy-service.
 * Tracks PnL, exposure, inventory, and strategy-specific metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyMetricsService {

    private final PolybotMetrics metrics;

    // PnL Gauges
    private AtomicReference<BigDecimal> dailyRealizedPnl;
    private AtomicReference<BigDecimal> unrealizedPnl;
    private AtomicReference<BigDecimal> cumulativePnl;

    // Position/Risk Gauges
    private AtomicReference<BigDecimal> totalExposure;
    private AtomicReference<BigDecimal> bankroll;
    private AtomicReference<BigDecimal> inventoryImbalance;

    // Strategy-specific gauges (Gabagool)
    private AtomicReference<BigDecimal> completeSetEdge;
    private AtomicReference<BigDecimal> activeMarketsCount;

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing strategy metrics...");

        // PnL metrics
        dailyRealizedPnl = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_daily_realized_pnl_usd",
                "Daily realized PnL in USD",
                BigDecimal.ZERO
        );

        unrealizedPnl = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_unrealized_pnl_usd",
                "Unrealized PnL in USD",
                BigDecimal.ZERO
        );

        cumulativePnl = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_cumulative_pnl_usd",
                "Cumulative PnL in USD since service start",
                BigDecimal.ZERO
        );

        // Position/Risk metrics
        totalExposure = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_total_exposure_usd",
                "Total position exposure in USD",
                BigDecimal.ZERO
        );

        bankroll = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_bankroll_usd",
                "Current bankroll in USD",
                BigDecimal.valueOf(1000.0) // Default starting bankroll
        );

        inventoryImbalance = metrics.registerAtomicBigDecimalGauge(
                "polybot_strategy_inventory_imbalance",
                "Inventory imbalance in shares (positive = long, negative = short)",
                BigDecimal.ZERO
        );

        // Gabagool-specific metrics
        completeSetEdge = metrics.registerAtomicBigDecimalGauge(
                "polybot_gabagool_complete_set_edge",
                "Complete-set arbitrage edge (as decimal, e.g., 0.01 = 1%)",
                BigDecimal.ZERO
        );

        activeMarketsCount = metrics.registerAtomicBigDecimalGauge(
                "polybot_gabagool_active_markets_count",
                "Number of active markets being traded",
                BigDecimal.ZERO
        );

        log.info("Strategy metrics initialized successfully");
    }

    // PnL Updates
    public void updateDailyRealizedPnl(BigDecimal pnl) {
        dailyRealizedPnl.set(pnl != null ? pnl : BigDecimal.ZERO);
    }

    public void updateUnrealizedPnl(BigDecimal pnl) {
        unrealizedPnl.set(pnl != null ? pnl : BigDecimal.ZERO);
    }

    public void updateCumulativePnl(BigDecimal pnl) {
        cumulativePnl.set(pnl != null ? pnl : BigDecimal.ZERO);
    }

    public void addToCumulativePnl(BigDecimal delta) {
        if (delta != null) {
            cumulativePnl.updateAndGet(current -> current.add(delta));
        }
    }

    // Position/Risk Updates
    public void updateTotalExposure(BigDecimal exposure) {
        totalExposure.set(exposure != null ? exposure : BigDecimal.ZERO);
    }

    public void updateBankroll(BigDecimal amount) {
        bankroll.set(amount != null ? amount : BigDecimal.ZERO);
    }

    public void updateInventoryImbalance(BigDecimal imbalance) {
        inventoryImbalance.set(imbalance != null ? imbalance : BigDecimal.ZERO);
    }

    // Gabagool Strategy Updates
    public void updateCompleteSetEdge(BigDecimal edge) {
        completeSetEdge.set(edge != null ? edge : BigDecimal.ZERO);
    }

    public void updateActiveMarketsCount(int count) {
        activeMarketsCount.set(BigDecimal.valueOf(count));
    }
}
