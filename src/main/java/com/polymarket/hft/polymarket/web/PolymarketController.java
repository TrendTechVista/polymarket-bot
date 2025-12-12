package com.polymarket.hft.polymarket.web;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.service.PolymarketTradingService;
import com.polymarket.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polymarket.hft.polymarket.ws.TopOfBook;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/polymarket")
@Validated
public class PolymarketController {

    private final PolymarketTradingService tradingService;
    private final ClobMarketWebSocketClient marketWebSocketClient;

    public PolymarketController(PolymarketTradingService tradingService, ClobMarketWebSocketClient marketWebSocketClient) {
        this.tradingService = tradingService;
        this.marketWebSocketClient = marketWebSocketClient;
    }

    @GetMapping("/orderbook/{tokenId}")
    public ResponseEntity<OrderBook> getOrderBook(@PathVariable String tokenId) {
        return ResponseEntity.ok(tradingService.getOrderBook(tokenId));
    }

    @GetMapping("/tick-size/{tokenId}")
    public ResponseEntity<BigDecimal> getTickSize(@PathVariable String tokenId) {
        return ResponseEntity.ok(tradingService.getTickSize(tokenId));
    }

    @GetMapping("/neg-risk/{tokenId}")
    public ResponseEntity<Boolean> isNegRisk(@PathVariable String tokenId) {
        return ResponseEntity.ok(tradingService.isNegRisk(tokenId));
    }

    @GetMapping("/marketdata/top/{tokenId}")
    public ResponseEntity<TopOfBook> getTopOfBook(@PathVariable String tokenId) {
        return marketWebSocketClient.getTopOfBook(tokenId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/limit")
    public ResponseEntity<OrderSubmissionResult> placeLimitOrder(@Valid @RequestBody LimitOrderRequest request) {
        return ResponseEntity.ok(tradingService.placeLimitOrder(request));
    }

    @PostMapping("/orders/market")
    public ResponseEntity<OrderSubmissionResult> placeMarketOrder(@Valid @RequestBody MarketOrderRequest request) {
        return ResponseEntity.ok(tradingService.placeMarketOrder(request));
    }

    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<JsonNode> cancelOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(tradingService.cancelOrder(orderId));
    }
}
