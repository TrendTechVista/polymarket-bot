package com.polymarket.hft.web;

import java.util.concurrent.CompletableFuture;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.polymarket.hft.domain.OrderRequest;
import com.polymarket.hft.domain.OrderResponse;
import com.polymarket.hft.service.TradingService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
@Validated
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    @PostMapping
    public CompletableFuture<ResponseEntity<OrderResponse>> submitOrder(
            @Valid @RequestBody OrderRequest request) {
        return tradingService.submitOrder(request)
                .thenApply(ResponseEntity::ok);
    }
}

