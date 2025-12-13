package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.service.PolymarketTradingService;
import com.polymarket.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polymarket.hft.polymarket.ws.TopOfBook;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/polymarket")
@Validated
@RequiredArgsConstructor
@Slf4j
public class PolymarketController {

  private final @NonNull PolymarketTradingService tradingService;
  private final @NonNull ClobMarketWebSocketClient marketWebSocketClient;

  @GetMapping("/health")
  public ResponseEntity<PolymarketHealthResponse> getHealth(
      @RequestParam(name = "deep", required = false, defaultValue = "false") boolean deep,
      @RequestParam(name = "tokenId", required = false) String tokenId
  ) {
    log.info("api /health deep={} tokenId={}", deep, tokenId);
    return ResponseEntity.ok(tradingService.getHealth(deep, tokenId));
  }

  @GetMapping("/orderbook/{tokenId}")
  public ResponseEntity<OrderBook> getOrderBook(@PathVariable String tokenId) {
    log.info("api /orderbook tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.getOrderBook(tokenId));
  }

  @GetMapping("/tick-size/{tokenId}")
  public ResponseEntity<BigDecimal> getTickSize(@PathVariable String tokenId) {
    log.info("api /tick-size tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.getTickSize(tokenId));
  }

  @GetMapping("/neg-risk/{tokenId}")
  public ResponseEntity<Boolean> isNegRisk(@PathVariable String tokenId) {
    log.info("api /neg-risk tokenId={}", tokenId);
    return ResponseEntity.ok(tradingService.isNegRisk(tokenId));
  }

  @GetMapping("/marketdata/top/{tokenId}")
  public ResponseEntity<TopOfBook> getTopOfBook(@PathVariable String tokenId) {
    log.info("api /marketdata/top tokenId={}", tokenId);
    return marketWebSocketClient.getTopOfBook(tokenId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/orders/limit")
  public ResponseEntity<OrderSubmissionResult> placeLimitOrder(@Valid @RequestBody LimitOrderRequest request) {
    log.info("api /orders/limit tokenId={} side={} price={} size={} orderType={}",
        request.tokenId(), request.side(), request.price(), request.size(), request.orderType());
    return ResponseEntity.ok(tradingService.placeLimitOrder(request));
  }

  @PostMapping("/orders/market")
  public ResponseEntity<OrderSubmissionResult> placeMarketOrder(@Valid @RequestBody MarketOrderRequest request) {
    log.info("api /orders/market tokenId={} side={} amount={} price={} orderType={}",
        request.tokenId(), request.side(), request.amount(), request.price(), request.orderType());
    return ResponseEntity.ok(tradingService.placeMarketOrder(request));
  }

  @DeleteMapping("/orders/{orderId}")
  public ResponseEntity<JsonNode> cancelOrder(@PathVariable String orderId) {
    log.info("api /orders/cancel orderId={}", orderId);
    return ResponseEntity.ok(tradingService.cancelOrder(orderId));
  }
}
