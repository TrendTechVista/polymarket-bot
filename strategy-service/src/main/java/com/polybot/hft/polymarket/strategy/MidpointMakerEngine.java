package com.polybot.hft.polymarket.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.model.ClobOrderType;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class MidpointMakerEngine {

  private static final MathContext MIDPOINT_CTX = new MathContext(18, RoundingMode.HALF_UP);
  private static final Duration TICK_TTL = Duration.ofMinutes(5);

  private final @NonNull HftProperties properties;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull ExecutorApiClient executorApi;

  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "midpoint-maker");
    t.setDaemon(true);
    return t;
  });
  private final Map<String, QuoteState> quotesByTokenId = new ConcurrentHashMap<>();
  private final Map<String, TickCacheEntry> tickByTokenId = new ConcurrentHashMap<>();

  private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
    if (v.compareTo(min) < 0) {
      return min;
    }
    if (v.compareTo(max) > 0) {
      return max;
    }
    return v;
  }

  private static String resolveOrderId(OrderSubmissionResult result) {
    if (result == null) {
      return null;
    }
    if (result.mode() == HftProperties.TradingMode.PAPER) {
      return "paper-" + UUID.randomUUID();
    }
    JsonNode resp = result.clobResponse();
    if (resp == null) {
      return null;
    }
    if (resp.hasNonNull("orderID")) {
      return resp.get("orderID").asText();
    }
    if (resp.hasNonNull("orderId")) {
      return resp.get("orderId").asText();
    }
    if (resp.hasNonNull("order_id")) {
      return resp.get("order_id").asText();
    }
    return null;
  }

  @PostConstruct
  void startIfEnabled() {
    HftProperties.MidpointMaker cfg = properties.strategy().midpointMaker();
    if (!cfg.enabled()) {
      return;
    }
    if (!properties.polymarket().marketWsEnabled()) {
      log.warn("midpoint-maker enabled, but market WS disabled (hft.polymarket.market-ws-enabled=false).");
      return;
    }
    List<String> tokenIds = properties.polymarket().marketAssetIds();
    if (tokenIds == null || tokenIds.isEmpty()) {
      log.warn("midpoint-maker enabled, but no token IDs configured (hft.polymarket.market-asset-ids).");
      return;
    }

    long periodMs = Math.max(50, cfg.refreshMillis());
    executor.scheduleAtFixedRate(() -> tick(tokenIds, cfg), 0, periodMs, TimeUnit.MILLISECONDS);
    log.info("midpoint-maker started (tokens={}, refreshMillis={})", tokenIds.size(), periodMs);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private void tick(List<String> tokenIds, HftProperties.MidpointMaker cfg) {
    for (String tokenId : tokenIds) {
      try {
        tickOne(tokenId, cfg);
      } catch (Exception e) {
        log.debug("midpoint-maker tick error for {}: {}", tokenId, e.toString());
      }
    }
  }

  private void tickOne(String tokenId, HftProperties.MidpointMaker cfg) {
    TopOfBook tob = marketWs.getTopOfBook(tokenId).orElse(null);
    if (tob == null || tob.bestBid() == null || tob.bestAsk() == null) {
      return;
    }
    if (tob.bestBid().compareTo(BigDecimal.ZERO) <= 0 || tob.bestAsk().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if (tob.bestBid().compareTo(tob.bestAsk()) >= 0) {
      return;
    }

    Instant now = Instant.now();
    BigDecimal tickSize = tickSize(tokenId, now);

    BigDecimal midpoint = tob.bestBid().add(tob.bestAsk()).divide(BigDecimal.valueOf(2), MIDPOINT_CTX);
    BigDecimal halfSpread = cfg.spread().divide(BigDecimal.valueOf(2), MIDPOINT_CTX);

    BigDecimal bidPrice = roundToTick(midpoint.subtract(halfSpread), tickSize, RoundingMode.DOWN);
    BigDecimal askPrice = roundToTick(midpoint.add(halfSpread), tickSize, RoundingMode.UP);

    BigDecimal minPrice = tickSize;
    BigDecimal maxPrice = BigDecimal.ONE.subtract(tickSize);
    bidPrice = clamp(bidPrice, minPrice, maxPrice);
    askPrice = clamp(askPrice, minPrice, maxPrice);
    if (bidPrice.compareTo(askPrice) >= 0) {
      return;
    }

    QuoteState prev = quotesByTokenId.remove(tokenId);
    if (prev != null) {
      safeCancel(prev.buyOrderId);
      safeCancel(prev.sellOrderId);
    }

    OrderSubmissionResult buy = executorApi.placeLimitOrder(new LimitOrderRequest(
        tokenId,
        OrderSide.BUY,
        bidPrice,
        cfg.quoteSize(),
        ClobOrderType.GTC,
        tickSize,
        null,
        null,
        null,
        null,
        null,
        false
    ));

    OrderSubmissionResult sell = executorApi.placeLimitOrder(new LimitOrderRequest(
        tokenId,
        OrderSide.SELL,
        askPrice,
        cfg.quoteSize(),
        ClobOrderType.GTC,
        tickSize,
        null,
        null,
        null,
        null,
        null,
        false
    ));

    quotesByTokenId.put(tokenId, new QuoteState(
        resolveOrderId(buy),
        resolveOrderId(sell)
    ));
  }

  private void safeCancel(String orderId) {
    if (orderId == null || orderId.isBlank()) {
      return;
    }
    try {
      executorApi.cancelOrder(orderId);
    } catch (Exception ignored) {
    }
  }

  private record QuoteState(String buyOrderId, String sellOrderId) {
  }

  private BigDecimal tickSize(String tokenId, Instant now) {
    TickCacheEntry cached = tickByTokenId.get(tokenId);
    if (cached != null && Duration.between(cached.fetchedAt, now).compareTo(TICK_TTL) <= 0) {
      return cached.tickSize;
    }
    BigDecimal tick = executorApi.getTickSize(tokenId);
    tickByTokenId.put(tokenId, new TickCacheEntry(tick, now));
    return tick;
  }

  private static BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize, RoundingMode mode) {
    if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
      return value;
    }
    BigDecimal ticks = value.divide(tickSize, 0, mode);
    return ticks.multiply(tickSize);
  }

  private record TickCacheEntry(BigDecimal tickSize, Instant fetchedAt) {
  }
}
