package com.polymarket.hft.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.stereotype.Service;

import com.polymarket.hft.domain.OrderRequest;
import com.polymarket.hft.domain.OrderResponse;

@Service
public class TradingService {

    private final ExecutorService tradingExecutor;
    private final Clock clock;

    public TradingService(ExecutorService tradingExecutor, Clock clock) {
        this.tradingExecutor = tradingExecutor;
        this.clock = clock;
    }

    public CompletableFuture<OrderResponse> submitOrder(OrderRequest request) {
        return CompletableFuture.supplyAsync(() -> acceptOrder(request), tradingExecutor);
    }

    private OrderResponse acceptOrder(OrderRequest request) {
        // TODO wire in actual exchange gateway or strategy module.
        Instant acceptedAt = clock.instant();
        UUID orderId = UUID.randomUUID();
        String message = "Accepted %s %s @ %s".formatted(
                request.side(),
                request.instrument(),
                request.price());
        return new OrderResponse(orderId, acceptedAt, message);
    }
}

