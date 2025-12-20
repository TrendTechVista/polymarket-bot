package com.polybot.ingestor.metrics;

import com.polybot.hft.metrics.PolybotMetrics;
import io.micrometer.core.instrument.Tag;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Metrics service for ingestor-service.
 * Tracks WebSocket connection status and message throughput.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestorMetricsService {

    private final PolybotMetrics metrics;

    private AtomicReference<Boolean> marketWebSocketConnected;

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing ingestor metrics...");

        // WebSocket connection status
        marketWebSocketConnected = new AtomicReference<>(false);

        metrics.registerBooleanGauge(
                "polybot_websocket_connected",
                "WebSocket connection status (1 = connected, 0 = disconnected)",
                marketWebSocketConnected::get,
                Tag.of("type", "market")
        );

        log.info("Ingestor metrics initialized successfully");
    }

    /**
     * Update the market WebSocket connection status.
     */
    public void setMarketWebSocketConnected(boolean connected) {
        marketWebSocketConnected.set(connected);
        log.debug("Market WebSocket connected status updated to: {}", connected);
    }
}
