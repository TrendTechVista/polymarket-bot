package com.polybot.hft.executor.metrics;

import com.polybot.hft.metrics.PolybotMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics service for executor-service.
 * Tracks order placement, fills, cancellations, and slippage.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutorMetricsService {

    private final PolybotMetrics metrics;

    // Counters
    private Counter ordersPlacedCounter;
    private Counter ordersFilledCounter;
    private Counter ordersFilledTotalCounter;
    private Counter ordersCancelledCounter;
    private Counter ordersCancelledTotalCounter;
    private Counter ordersRejectedCounter;
    private Counter ordersRejectedTotalCounter;

    // Gauges
    private AtomicReference<BigDecimal> averageSlippageTicks;

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing executor metrics...");

        // Order counters
        ordersPlacedCounter = metrics.createCounter(
                "polybot_orders_placed_total",
                "Total number of orders placed"
        );

        ordersFilledCounter = metrics.createCounter(
                "polybot_orders_filled_total",
                "Total number of orders fully filled"
        );

        ordersCancelledCounter = metrics.createCounter(
                "polybot_orders_cancelled_total",
                "Total number of orders cancelled"
        );

        ordersRejectedCounter = metrics.createCounter(
                "polybot_orders_rejected_total",
                "Total number of orders rejected"
        );

        // Order status breakdown (with labels)
        ordersFilledTotalCounter = metrics.createCounter(
                "polybot_orders_total",
                "Total orders by status",
                Tag.of("status", "filled")
        );

        ordersCancelledTotalCounter = metrics.createCounter(
                "polybot_orders_total",
                "Total orders by status",
                Tag.of("status", "cancelled")
        );

        ordersRejectedTotalCounter = metrics.createCounter(
                "polybot_orders_total",
                "Total orders by status",
                Tag.of("status", "rejected")
        );

        // Slippage gauge
        averageSlippageTicks = metrics.registerAtomicBigDecimalGauge(
                "polybot_order_slippage_ticks",
                "Average slippage in ticks for filled orders",
                BigDecimal.ZERO
        );

        log.info("Executor metrics initialized successfully");
    }

    /**
     * Record an order placement.
     */
    public void recordOrderPlaced() {
        ordersPlacedCounter.increment();
    }

    /**
     * Record an order fill.
     * @param slippageTicks The slippage in ticks (can be null)
     */
    public void recordOrderFilled(BigDecimal slippageTicks) {
        ordersFilledCounter.increment();
        ordersFilledTotalCounter.increment();

        if (slippageTicks != null) {
            averageSlippageTicks.set(slippageTicks);
        }
    }

    /**
     * Record an order cancellation.
     */
    public void recordOrderCancelled() {
        ordersCancelledCounter.increment();
        ordersCancelledTotalCounter.increment();
    }

    /**
     * Record an order rejection.
     */
    public void recordOrderRejected() {
        ordersRejectedCounter.increment();
        ordersRejectedTotalCounter.increment();
    }

    /**
     * Update the average slippage metric.
     */
    public void updateAverageSlippage(BigDecimal slippageTicks) {
        if (slippageTicks != null) {
            averageSlippageTicks.set(slippageTicks);
        }
    }
}
