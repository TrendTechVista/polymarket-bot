package com.polybot.hft.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Central metrics registry for Polybot services.
 * Provides convenience methods for creating and managing Prometheus metrics.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolybotMetrics {

    private final MeterRegistry registry;

    /**
     * Register a gauge that tracks a BigDecimal value supplier.
     */
    public void registerBigDecimalGauge(String name, String description, Supplier<BigDecimal> valueSupplier, Tag... tags) {
        Gauge.builder(name, valueSupplier, supplier -> {
            BigDecimal value = supplier.get();
            return value != null ? value.doubleValue() : 0.0;
        })
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Registered gauge: {} with description: {}", name, description);
    }

    /**
     * Register a gauge that tracks a double value supplier.
     */
    public void registerDoubleGauge(String name, String description, Supplier<Double> valueSupplier, Tag... tags) {
        Gauge.builder(name, valueSupplier, supplier -> {
            Double value = supplier.get();
            return value != null ? value : 0.0;
        })
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Registered gauge: {} with description: {}", name, description);
    }

    /**
     * Register a gauge that tracks an integer value supplier.
     */
    public void registerIntGauge(String name, String description, Supplier<Integer> valueSupplier, Tag... tags) {
        Gauge.builder(name, valueSupplier, supplier -> {
            Integer value = supplier.get();
            return value != null ? value.doubleValue() : 0.0;
        })
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Registered gauge: {} with description: {}", name, description);
    }

    /**
     * Register a gauge that tracks a boolean value (1 = true, 0 = false).
     */
    public void registerBooleanGauge(String name, String description, Supplier<Boolean> valueSupplier, Tag... tags) {
        Gauge.builder(name, valueSupplier, supplier -> {
            Boolean value = supplier.get();
            return Boolean.TRUE.equals(value) ? 1.0 : 0.0;
        })
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Registered boolean gauge: {} with description: {}", name, description);
    }

    /**
     * Register an AtomicReference gauge for mutable values.
     */
    public AtomicReference<BigDecimal> registerAtomicBigDecimalGauge(String name, String description, BigDecimal initialValue, Tag... tags) {
        AtomicReference<BigDecimal> ref = new AtomicReference<>(initialValue != null ? initialValue : BigDecimal.ZERO);
        Gauge.builder(name, ref, atomicRef -> {
            BigDecimal value = atomicRef.get();
            return value != null ? value.doubleValue() : 0.0;
        })
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Registered atomic gauge: {} with description: {}", name, description);
        return ref;
    }

    /**
     * Create a counter with optional tags.
     */
    public Counter createCounter(String name, String description, Tag... tags) {
        Counter counter = Counter.builder(name)
                .description(description)
                .tags(List.of(tags))
                .register(registry);
        log.debug("Created counter: {} with description: {}", name, description);
        return counter;
    }

    /**
     * Increment a counter by name (creates if doesn't exist).
     */
    public void incrementCounter(String name, Tag... tags) {
        Counter.builder(name)
                .tags(List.of(tags))
                .register(registry)
                .increment();
    }

    /**
     * Get the underlying meter registry for advanced usage.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
}
