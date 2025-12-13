package com.polybot.hft.polymarket.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.model.ClobOrderType;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class HouseEdgeEngine {

  private static final Duration META_TTL = Duration.ofMinutes(5);

  private final @NonNull HftProperties properties;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull ExecutorApiClient executorApi;
  private final @NonNull Clock clock;

  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "house-edge");
    t.setDaemon(true);
    return t;
  });

  private final Map<String, TradeWindow> tradeWindowsByTokenId = new ConcurrentHashMap<>();
  private final Map<String, MarketMeta> metaByTokenId = new ConcurrentHashMap<>();
  private final Map<String, MarketState> stateByMarketKey = new ConcurrentHashMap<>();

  @PostConstruct
  void startIfEnabled() {
    HftProperties.HouseEdge cfg = properties.strategy().houseEdge();
    if (!cfg.enabled()) {
      return;
    }
    if (!properties.polymarket().marketWsEnabled()) {
      log.warn("house-edge enabled, but market WS disabled (hft.polymarket.market-ws-enabled=false).");
      return;
    }
    if (cfg.markets().isEmpty()) {
      log.warn("house-edge enabled, but no markets configured (hft.strategy.house-edge.markets).");
      return;
    }
    if (cfg.loserInventoryLimit().compareTo(BigDecimal.ZERO) > 0) {
      log.warn("house-edge loser-inventory-limit is configured but inventory tracking is not implemented yet; the kill switch is not enforced.");
    }

    Set<String> subscribed = new HashSet<>(properties.polymarket().marketAssetIds());
    for (HftProperties.HouseEdgeMarket m : cfg.markets()) {
      if (m.yesTokenId() == null || m.yesTokenId().isBlank() || m.noTokenId() == null || m.noTokenId().isBlank()) {
        log.warn("house-edge market entry missing yes/no token ids: {}", m);
        continue;
      }
      if (!subscribed.contains(m.yesTokenId()) || !subscribed.contains(m.noTokenId())) {
        log.warn("house-edge market token ids must be in hft.polymarket.market-asset-ids (yes={}, no={})",
            suffix(m.yesTokenId()), suffix(m.noTokenId()));
      }
    }

    long periodMs = Math.max(50, cfg.refreshMillis());
    executor.scheduleAtFixedRate(() -> tick(cfg), 0, periodMs, TimeUnit.MILLISECONDS);
    log.info("house-edge started (markets={}, refreshMillis={})", cfg.markets().size(), periodMs);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private void tick(HftProperties.HouseEdge cfg) {
    for (HftProperties.HouseEdgeMarket market : cfg.markets()) {
      try {
        tickMarket(cfg, market);
      } catch (Exception e) {
        log.debug("house-edge tick error for market yes={} no={}: {}", suffix(market.yesTokenId()), suffix(market.noTokenId()), e.toString());
      }
    }
  }

  private void tickMarket(HftProperties.HouseEdge cfg, HftProperties.HouseEdgeMarket market) {
    String yesTokenId = safe(market.yesTokenId());
    String noTokenId = safe(market.noTokenId());
    if (yesTokenId.isBlank() || noTokenId.isBlank()) {
      return;
    }

    TopOfBook yesTob = marketWs.getTopOfBook(yesTokenId).orElse(null);
    if (yesTob == null) {
      return;
    }

    Instant now = Instant.now(clock);
    if (yesTob.lastTradePrice() != null && yesTob.lastTradeAt() != null) {
      tradeWindowsByTokenId
          .computeIfAbsent(yesTokenId, k -> new TradeWindow(cfg.tradeWindowSeconds(), cfg.tradeSamples()))
          .add(yesTob.lastTradePrice(), yesTob.lastTradeAt(), now);
    }

    BigDecimal currentYes = currentPrice(yesTob);
    BigDecimal fair = tradeWindowsByTokenId.getOrDefault(yesTokenId, TradeWindow.empty())
        .fairValue(now)
        .orElse(null);
    if (currentYes == null || fair == null) {
      return;
    }

    Bias bias = currentYes.compareTo(fair) > 0 ? Bias.ACCUMULATE_YES : Bias.ACCUMULATE_NO;
    String marketKey = marketKey(market);
    MarketState prev = stateByMarketKey.get(marketKey);
    if (prev == null || prev.bias != bias) {
      stateByMarketKey.put(marketKey, new MarketState(bias));
      log.info("house-edge bias change market={} bias={} currentYes={} fairYes={}",
          marketKey,
          bias.name(),
          currentYes,
          fair);
      if (prev != null) {
        safeCancel(prev.buyOrderId);
        safeCancel(prev.sellOrderId);
      }
    }

    String tokenId = bias == Bias.ACCUMULATE_YES ? yesTokenId : noTokenId;
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

    MarketMeta meta = meta(tokenId, now);
    QuotePrices prices = quotePrices(cfg, tob, meta.tickSize);
    if (prices == null) {
      return;
    }

    MarketState state = stateByMarketKey.get(marketKey);
    if (state == null) {
      return;
    }

    boolean shouldReplaceBuy = state.buyPrice == null || state.buyPrice.compareTo(prices.buy) != 0;
    boolean shouldReplaceSell = state.sellPrice == null || state.sellPrice.compareTo(prices.sell) != 0;
    if (!shouldReplaceBuy && !shouldReplaceSell) {
      return;
    }

    if (shouldReplaceBuy) {
      safeCancel(state.buyOrderId);
      OrderSubmissionResult buy = placeLimit(tokenId, OrderSide.BUY, prices.buy, cfg.quoteSize(), meta);
      state.buyOrderId = resolveOrderId(buy);
      state.buyPrice = prices.buy;
    }
    if (shouldReplaceSell) {
      safeCancel(state.sellOrderId);
      OrderSubmissionResult sell = placeLimit(tokenId, OrderSide.SELL, prices.sell, cfg.quoteSize(), meta);
      state.sellOrderId = resolveOrderId(sell);
      state.sellPrice = prices.sell;
    }
  }

  private OrderSubmissionResult placeLimit(
      String tokenId,
      OrderSide side,
      BigDecimal price,
      BigDecimal size,
      MarketMeta meta
  ) {
    return executorApi.placeLimitOrder(new LimitOrderRequest(
        tokenId,
        side,
        price,
        size,
        ClobOrderType.GTC,
        meta.tickSize,
        null,
        null,
        null,
        null,
        null,
        false
    ));
  }

  private MarketMeta meta(String tokenId, Instant now) {
    MarketMeta cached = metaByTokenId.get(tokenId);
    if (cached != null && Duration.between(cached.fetchedAt, now).compareTo(META_TTL) <= 0) {
      return cached;
    }
    BigDecimal tick = executorApi.getTickSize(tokenId);
    MarketMeta meta = new MarketMeta(tick, now);
    metaByTokenId.put(tokenId, meta);
    return meta;
  }

  private static QuotePrices quotePrices(HftProperties.HouseEdge cfg, TopOfBook tob, BigDecimal tickSize) {
    BigDecimal bestBid = tob.bestBid();
    BigDecimal bestAsk = tob.bestAsk();
    if (bestBid == null || bestAsk == null || tickSize == null) {
      return null;
    }

    BigDecimal minPrice = tickSize;
    BigDecimal maxPrice = BigDecimal.ONE.subtract(tickSize);
    BigDecimal improve = tickSize.multiply(BigDecimal.valueOf(cfg.aggressiveImproveTicks()));
    BigDecimal away = tickSize.multiply(BigDecimal.valueOf(cfg.passiveAwayTicks()));

    BigDecimal maxBuy = bestAsk.subtract(tickSize);
    if (maxBuy.compareTo(minPrice) <= 0) {
      return null;
    }

    BigDecimal rawBuy = bestBid.add(improve);
    rawBuy = rawBuy.min(maxBuy);
    rawBuy = clamp(rawBuy, minPrice, maxPrice);

    BigDecimal rawSell = bestAsk.add(away);
    rawSell = clamp(rawSell, minPrice, maxPrice);

    BigDecimal buy = roundToTick(rawBuy, tickSize, RoundingMode.DOWN);
    BigDecimal sell = roundToTick(rawSell, tickSize, RoundingMode.UP);
    if (buy.compareTo(sell) >= 0) {
      return null;
    }
    return new QuotePrices(buy, sell);
  }

  private static BigDecimal currentPrice(TopOfBook tob) {
    if (tob.lastTradePrice() != null) {
      return tob.lastTradePrice();
    }
    if (tob.bestBid() != null && tob.bestAsk() != null && tob.bestBid().compareTo(BigDecimal.ZERO) > 0 && tob.bestAsk().compareTo(BigDecimal.ZERO) > 0) {
      return tob.bestBid().add(tob.bestAsk()).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP);
    }
    return null;
  }

  private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
    if (v.compareTo(min) < 0) {
      return min;
    }
    if (v.compareTo(max) > 0) {
      return max;
    }
    return v;
  }

  private static BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize, RoundingMode mode) {
    if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
      return value;
    }
    BigDecimal ticks = value.divide(tickSize, 0, mode);
    return ticks.multiply(tickSize);
  }

  private static void safeCancel(String orderId, ExecutorApiClient executorApi) {
    if (orderId == null || orderId.isBlank()) {
      return;
    }
    try {
      executorApi.cancelOrder(orderId);
    } catch (Exception ignored) {
    }
  }

  private void safeCancel(String orderId) {
    safeCancel(orderId, executorApi);
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

  private static String marketKey(HftProperties.HouseEdgeMarket m) {
    if (m.name() != null && !m.name().isBlank()) {
      return m.name();
    }
    return suffix(m.yesTokenId()) + ":" + suffix(m.noTokenId());
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }

  private static String suffix(String tokenId) {
    if (tokenId == null) {
      return "null";
    }
    String t = tokenId.trim();
    if (t.length() <= 6) {
      return t;
    }
    return "..." + t.substring(t.length() - 6);
  }

  private enum Bias {
    ACCUMULATE_YES,
    ACCUMULATE_NO,
  }

  private static final class MarketState {
    private final Bias bias;
    private volatile String buyOrderId;
    private volatile BigDecimal buyPrice;
    private volatile String sellOrderId;
    private volatile BigDecimal sellPrice;

    private MarketState(Bias bias) {
      this.bias = bias;
    }
  }

  private record QuotePrices(BigDecimal buy, BigDecimal sell) {
  }

  private record MarketMeta(BigDecimal tickSize, Instant fetchedAt) {
  }

  private static final class TradeWindow {
    private static final TradeWindow EMPTY = new TradeWindow(30L, 10);

    private final Duration window;
    private final int maxSamples;
    private final Deque<Trade> trades = new ArrayDeque<>();

    private Instant lastTradeAt = Instant.EPOCH;

    private TradeWindow(long windowSeconds, int maxSamples) {
      this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
      this.maxSamples = Math.max(1, maxSamples);
    }

    static TradeWindow empty() {
      return EMPTY;
    }

    void add(BigDecimal price, Instant tradeAt, Instant now) {
      if (price == null || tradeAt == null) {
        return;
      }
      if (!tradeAt.isAfter(lastTradeAt)) {
        prune(now);
        return;
      }
      lastTradeAt = tradeAt;
      trades.addLast(new Trade(price, tradeAt));
      prune(now);
      while (trades.size() > maxSamples) {
        trades.removeFirst();
      }
    }

    Optional<BigDecimal> fairValue(Instant now) {
      prune(now);
      if (trades.isEmpty()) {
        return Optional.empty();
      }
      BigDecimal sum = BigDecimal.ZERO;
      for (Trade t : trades) {
        sum = sum.add(t.price);
      }
      return Optional.of(sum.divide(BigDecimal.valueOf(trades.size()), 18, RoundingMode.HALF_UP));
    }

    private void prune(Instant now) {
      if (trades.isEmpty()) {
        return;
      }
      Instant cutoff = now.minus(window);
      while (!trades.isEmpty() && trades.peekFirst().at.isBefore(cutoff)) {
        trades.removeFirst();
      }
    }

    private record Trade(BigDecimal price, Instant at) {
    }
  }
}
