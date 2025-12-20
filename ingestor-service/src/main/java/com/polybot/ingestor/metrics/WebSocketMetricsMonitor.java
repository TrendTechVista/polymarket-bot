package com.polybot.ingestor.metrics;

import com.polybot.ingestor.metrics.IngestorMetricsService;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors WebSocket connection status and updates metrics.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketMetricsMonitor {

    private final ClobMarketWebSocketClient marketWs;
    private final IngestorMetricsService metricsService;

    @Scheduled(initialDelay = 5_000L, fixedDelay = 10_000L)
    public void updateConnectionStatus() {
        // TODO: Add proper connection status tracking to ClobMarketWebSocketClient
        // For now, assume connected if service is running
        boolean connected = marketWs != null;
        metricsService.setMarketWebSocketConnected(connected);
    }
}
