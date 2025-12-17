package com.polybot.ingestor.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.ingestor.config.IngestorProperties;
import com.polybot.ingestor.polymarket.PolymarketClobApiClient;
import com.polybot.ingestor.polymarket.PolymarketDataApiClient;
import com.polybot.ingestor.polymarket.PolymarketGammaApiClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketMarketContextIngestor {

  private static final String GAMMA_MARKET_EVENT_TYPE = "polymarket.gamma.market";
  private static final String CLOB_TOB_EVENT_TYPE = "polymarket.clob.tob";
  private static final String MARKET_TRADE_EVENT_TYPE = "polymarket.market.trade";

  private static final int DEFAULT_SEEN_MARKET_TRADE_KEYS_CAPACITY = 250_000;

  private final @NonNull IngestorProperties properties;
  private final @NonNull PolymarketGammaApiClient gammaApi;
  private final @NonNull PolymarketClobApiClient clobApi;
  private final @NonNull PolymarketDataApiClient dataApi;
  private final @NonNull HftEventPublisher events;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;

  private final EvictingKeySet seenMarketTradeKeys = new EvictingKeySet(DEFAULT_SEEN_MARKET_TRADE_KEYS_CAPACITY);

  private final AtomicLong publishedGammaSnapshots = new AtomicLong(0);
  private final AtomicLong publishedClobTobs = new AtomicLong(0);
  private final AtomicLong publishedMarketTrades = new AtomicLong(0);
  private final AtomicLong failures = new AtomicLong(0);
  private final AtomicLong gammaPolls = new AtomicLong(0);
  private volatile long lastGammaPollAtMillis;

  private final Object marketLock = new Object();
  private final Map<String, MarketState> markets = new LinkedHashMap<>(16, 0.75f, true);

  public void onUserTrade(
      String username,
      String proxyAddress,
      String userTradeKey,
      Instant userTradeAt,
      JsonNode userTrade
  ) {
    if (!properties.marketContext().enabled()) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }

    String slug = textOrNull(userTrade.path("slug"));
    if (slug == null) {
      return;
    }

    long nowMillis = Instant.now(clock).toEpochMilli();
    MarketState state;
    synchronized (marketLock) {
      state = markets.get(slug);
      if (state == null) {
        enforceMarketLimitLocked();
        state = new MarketState(slug, nowMillis);
        markets.put(slug, state);
        log.info("market-context tracking market slug={}", slug);
      }
      state.lastSeenAtMillis = nowMillis;
    }

    maybeFetchAndPublishGamma(username, proxyAddress, userTradeKey, state, nowMillis);

    long maxAgeSeconds = properties.marketContext().onTradeContextMaxAgeSeconds();
    long ageSeconds = Math.max(0, (nowMillis - userTradeAt.toEpochMilli()) / 1000L);
    if (ageSeconds <= maxAgeSeconds) {
      maybePublishClobTobContext(username, proxyAddress, userTradeKey, userTradeAt, userTrade, state, nowMillis);
      maybeFetchAndPublishMarketTrades(slug, state, nowMillis);
    }
  }

  @Scheduled(
      initialDelayString = "15000",
      fixedDelayString = "${ingestor.market-context.gamma-poll-interval-seconds:60}000"
  )
  public void pollGammaMarkets() {
    if (!properties.marketContext().enabled()) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }

    gammaPolls.incrementAndGet();
    lastGammaPollAtMillis = Instant.now(clock).toEpochMilli();
    long nowMillis = lastGammaPollAtMillis;
    long nowSeconds = nowMillis / 1000L;
    long lookaheadSeconds = properties.marketContext().gammaPollLookaheadSeconds();
    long maxIdleMillis = properties.marketContext().gammaPollMaxIdleSeconds() * 1000L;

    List<MarketState> snapshot;
    synchronized (marketLock) {
      snapshot = List.copyOf(markets.values());
    }
    for (MarketState state : snapshot) {
      if (state == null) {
        continue;
      }

      GammaMarket gamma = state.gamma;
        if (gamma != null) {
        if (isTerminalGammaMarket(gamma)) {
          continue;
        }
        if (maxIdleMillis > 0 && nowMillis - state.lastSeenAtMillis > maxIdleMillis) {
          continue;
        }
        Instant endDate = gamma.endDate();
        if (endDate != null) {
          long secondsToEnd = endDate.getEpochSecond() - nowSeconds;
          if (secondsToEnd > lookaheadSeconds) {
            continue;
          }
        }
      } else if (maxIdleMillis > 0 && nowMillis - state.lastSeenAtMillis > maxIdleMillis) {
        continue;
      }

      maybeFetchAndPublishGamma(null, null, null, state, nowMillis);
      sleep(properties.marketContext().requestDelayMillis());
    }
  }

  private void maybeFetchAndPublishGamma(
      String username,
      String proxyAddress,
      String userTradeKey,
      MarketState state,
      long nowMillis
  ) {
    long minIntervalMillis = properties.marketContext().gammaMinIntervalSeconds() * 1000L;
    if (nowMillis - state.lastGammaFetchAtMillis < minIntervalMillis) {
      return;
    }

    GammaMarket market;
    try {
      market = fetchGammaMarket(state.slug);
      if (market == null) {
        // Don't "lock out" the market for a full gammaMinInterval on transient failures.
        // Allow a quick retry while still throttling attempts.
        long retryMillis = Math.min(5_000L, minIntervalMillis);
        state.lastGammaFetchAtMillis = nowMillis - minIntervalMillis + retryMillis;
        return;
      }
    } catch (Exception e) {
      long retryMillis = Math.min(5_000L, minIntervalMillis);
      state.lastGammaFetchAtMillis = nowMillis - minIntervalMillis + retryMillis;
      failures.incrementAndGet();
      log.debug("market-context gamma fetch failed slug={} error={}", state.slug, e.toString());
      return;
    }

    state.lastGammaFetchAtMillis = nowMillis;
    state.gamma = market;
    String signature = gammaSignature(market);
    if (signature.equals(state.lastGammaSignature)) {
      return;
    }

    state.lastGammaSignature = signature;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("slug", market.slug());
    if (username != null && proxyAddress != null && userTradeKey != null) {
      data.put("trigger", Map.of(
          "username", username,
          "proxyAddress", proxyAddress,
          "tradeKey", userTradeKey
      ));
    }
    data.put("market", market.rawMarket());
    data.put("event", market.rawEvent());
    data.put("capturedAt", Instant.ofEpochMilli(nowMillis).toString());

    String key = "gamma:%s:%s:%s:%s".formatted(
        market.slug(),
        String.valueOf(market.closed()),
        String.valueOf(market.resolved()),
        market.resolution() == null ? "" : market.resolution()
    );
    events.publish(Instant.ofEpochMilli(nowMillis), GAMMA_MARKET_EVENT_TYPE, key, data);
    publishedGammaSnapshots.incrementAndGet();

    if (Boolean.TRUE.equals(market.resolved()) || market.resolution() != null) {
      log.info("market-context gamma resolved slug={} resolved={} resolution={}", market.slug(), market.resolved(), market.resolution());
    }
  }

  /**
   * Publishes a CLOB top-of-book snapshot for the traded token and, when Gamma market metadata is available,
   * also snapshots other outcomes in the same market at the same trigger time.
   */
  private void maybePublishClobTobContext(
      String username,
      String proxyAddress,
      String userTradeKey,
      Instant userTradeAt,
      JsonNode userTrade,
      MarketState state,
      long nowMillis
  ) {
    String tradedTokenId = textOrNull(userTrade.path("asset"));
    if (tradedTokenId == null) {
      return;
    }

    GammaMarket gamma = state.gamma;
    List<String> tokenIds = gamma == null ? List.of() : gamma.clobTokenIds();
    if (tokenIds != null && tokenIds.size() >= 2 && tokenIds.size() <= 4) {
      for (String tokenId : tokenIds) {
        if (tokenId == null || tokenId.isBlank()) {
          continue;
        }
        String tokenOutcome = outcomeForToken(gamma, tokenId);
        if ((tokenOutcome == null || tokenOutcome.isBlank()) && tokenId.equals(tradedTokenId)) {
          tokenOutcome = textOrNull(userTrade.path("outcome"));
        }
        maybePublishClobTobForToken(username, proxyAddress, userTradeKey, userTradeAt, state.slug, tokenId, tokenOutcome, userTrade, state, nowMillis);
      }
      return;
    }

    // Fallback: only snapshot the traded token.
    maybePublishClobTobForToken(
        username,
        proxyAddress,
        userTradeKey,
        userTradeAt,
        state.slug,
        tradedTokenId,
        textOrNull(userTrade.path("outcome")),
        userTrade,
        state,
        nowMillis
    );
  }

  private static String outcomeForToken(GammaMarket gamma, String tokenId) {
    if (gamma == null || tokenId == null || tokenId.isBlank()) {
      return null;
    }
    List<String> tokenIds = gamma.clobTokenIds();
    List<String> outcomes = gamma.outcomes();
    if (tokenIds == null || outcomes == null || tokenIds.isEmpty() || outcomes.isEmpty()) {
      return null;
    }
    int n = Math.min(tokenIds.size(), outcomes.size());
    for (int i = 0; i < n; i++) {
      if (tokenId.equals(tokenIds.get(i))) {
        return outcomes.get(i);
      }
    }
    return null;
  }

  private void maybePublishClobTobForToken(
      String username,
      String proxyAddress,
      String userTradeKey,
      Instant userTradeAt,
      String marketSlug,
      String tokenId,
      String tokenOutcome,
      JsonNode userTrade,
      MarketState state,
      long nowMillis
  ) {
    if (tokenId == null) {
      return;
    }

    long minIntervalMillis = properties.marketContext().clobBookMinIntervalSeconds() * 1000L;
    CachedTob cached = state.tobByTokenId.get(tokenId);
    if (cached != null && nowMillis - cached.fetchedAtMillis < minIntervalMillis) {
      publishClobTobEvent(
          username,
          proxyAddress,
          userTradeKey,
          userTradeAt,
          marketSlug,
          tokenId,
          userTrade,
          cached.tob,
          cached.fetchedAtMillis,
          true,
          nowMillis - cached.fetchedAtMillis,
          tokenOutcome
      );
      return;
    }

    JsonNode book;
    try {
      book = clobApi.getOrderBook(tokenId);
    } catch (Exception e) {
      failures.incrementAndGet();
      log.debug("market-context clob book fetch failed tokenId={} error={}", tokenId, e.toString());
      return;
    }
    if (PolymarketClobApiClient.isNotFoundError(book)) {
      return;
    }

    Map<String, Object> tob = topOfBook(book);
    state.tobByTokenId.put(tokenId, new CachedTob(nowMillis, tob));
    publishClobTobEvent(
        username,
        proxyAddress,
        userTradeKey,
        userTradeAt,
        marketSlug,
        tokenId,
        userTrade,
        tob,
        nowMillis,
        false,
        0L,
        tokenOutcome
    );
  }

  private void publishClobTobEvent(
      String username,
      String proxyAddress,
      String userTradeKey,
      Instant userTradeAt,
      String marketSlug,
      String tokenId,
      JsonNode userTrade,
      Map<String, Object> tob,
      long tobCapturedAtMillis,
      boolean fromCache,
      long cacheAgeMillis,
      String tokenOutcome
  ) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("marketSlug", marketSlug);
    data.put("tokenId", tokenId);
    data.put("outcome", tokenOutcome);
    data.put("trigger", Map.of(
        "username", username,
        "proxyAddress", proxyAddress,
        "tradeKey", userTradeKey,
        "tradeAt", userTradeAt.toString()
    ));
    data.put("book", tob);
    data.put("capturedAt", Instant.ofEpochMilli(tobCapturedAtMillis).toString());
    if (fromCache) {
      data.put("cached", true);
      data.put("cacheAgeMillis", cacheAgeMillis);
    }

    String key = "%s:%s:tob".formatted(userTradeKey, tokenId);
    events.publish(Instant.ofEpochMilli(tobCapturedAtMillis), CLOB_TOB_EVENT_TYPE, key, data);
    publishedClobTobs.incrementAndGet();
  }

  private void maybeFetchAndPublishMarketTrades(String slug, MarketState state, long nowMillis) {
    long minIntervalMillis = properties.marketContext().marketTradesMinIntervalSeconds() * 1000L;
    if (nowMillis - state.lastMarketTradesFetchAtMillis < minIntervalMillis) {
      return;
    }
    state.lastMarketTradesFetchAtMillis = nowMillis;

    int limit = properties.marketContext().marketTradesLimit();
    ArrayNode trades;
    try {
      trades = dataApi.getMarketTrades(slug, limit, 0);
    } catch (Exception e) {
      failures.incrementAndGet();
      log.debug("market-context market trades fetch failed slug={} error={}", slug, e.toString());
      return;
    }

    int published = 0;
    for (int i = trades.size() - 1; i >= 0; i--) {
      JsonNode trade = trades.get(i);
      if (trade == null || trade.isNull()) {
        continue;
      }

      long tsSeconds = trade.path("timestamp").asLong(0);
      Instant ts = tsSeconds > 0 ? Instant.ofEpochSecond(tsSeconds) : Instant.ofEpochMilli(nowMillis);
      String tx = trade.path("transactionHash").asText(null);
      String asset = trade.path("asset").asText("");
      String side = trade.path("side").asText("");
      String key = buildTradeKey(slug, tx, asset, side, tsSeconds);
      if (!seenMarketTradeKeys.add(key)) {
        continue;
      }

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("marketSlug", slug);
      data.put("trade", trade);
      data.put("capturedAt", Instant.ofEpochMilli(nowMillis).toString());

      events.publish(ts, MARKET_TRADE_EVENT_TYPE, key, data);
      publishedMarketTrades.incrementAndGet();
      published++;
    }

    if (published > 0) {
      log.info("market-context market trades slug={} newTrades={}", slug, published);
    }
  }

  private GammaMarket fetchGammaMarket(String slug) {
    PolymarketGammaApiClient.GammaMarketSnapshot snapshot = gammaApi.getMarketSnapshotBySlug(slug);
    if (snapshot == null) {
      return null;
    }
    JsonNode event = snapshot.event();
    JsonNode market = snapshot.market();

    String updatedAt = textOrNull(market.path("updatedAt"));
    String endDateIso = textOrNull(market.path("endDateIso"));
    if (endDateIso == null) {
      endDateIso = textOrNull(market.path("endDate"));
    }
    Instant endDate = parseInstantOrNull(endDateIso);

    String resolution = textOrNull(market.path("resolution"));
    Boolean resolved = boolOrNull(market.path("resolved"));
    Boolean closed = boolOrNull(market.path("closed"));
    String umaResolutionStatus = textOrNull(market.path("umaResolutionStatus"));

    List<String> outcomes = parseJsonStringArray(market.path("outcomes"));
    List<String> outcomePrices = parseJsonStringArray(market.path("outcomePrices"));
    List<String> tokenIds = parseJsonStringArray(market.path("clobTokenIds"));

    return new GammaMarket(
        slug,
        textOrNull(event.path("id")),
        textOrNull(event.path("slug")),
        textOrNull(event.path("title")),
        textOrNull(market.path("id")),
        textOrNull(market.path("conditionId")),
        endDate,
        closed,
        resolved,
        resolution,
        umaResolutionStatus,
        updatedAt,
        tokenIds,
        outcomes,
        outcomePrices,
        event,
        market
    );
  }

  private String gammaSignature(GammaMarket market) {
    String marketId = market.marketId() == null ? "" : market.marketId();
    String conditionId = market.conditionId() == null ? "" : market.conditionId();
    String resolution = market.resolution() == null ? "" : market.resolution();
    String resolved = market.resolved() == null ? "" : String.valueOf(market.resolved());
    String umaResolutionStatus = market.umaResolutionStatus() == null ? "" : market.umaResolutionStatus();
    String resolvedOutcome = resolvedOutcomeFromPrices(market.outcomes(), market.outcomePrices());
    if (resolvedOutcome == null) {
      resolvedOutcome = "";
    }
    String closed = market.closed() == null ? "" : String.valueOf(market.closed());
    String end = market.endDate() == null ? "" : market.endDate().toString();
    String tokenIds = String.join(",", market.clobTokenIds());
    String outcomes = String.join(",", market.outcomes());
    return "%s|%s|%s|%s|%s|%s|%s|%s|%s".formatted(
        marketId,
        conditionId,
        resolved,
        resolution,
        resolvedOutcome,
        umaResolutionStatus,
        closed,
        end,
        tokenIds + "::" + outcomes
    );
  }

  private static boolean isTerminalGammaMarket(GammaMarket market) {
    if (market == null) {
      return false;
    }
    if (Boolean.TRUE.equals(market.resolved())) {
      return true;
    }
    if (market.resolution() != null && !market.resolution().isBlank()) {
      return true;
    }
    return resolvedOutcomeFromPrices(market.outcomes(), market.outcomePrices()) != null;
  }

  private static String resolvedOutcomeFromPrices(List<String> outcomes, List<String> outcomePrices) {
    if (outcomes == null || outcomePrices == null) {
      return null;
    }
    if (outcomes.isEmpty() || outcomePrices.isEmpty() || outcomes.size() != outcomePrices.size()) {
      return null;
    }

    BigDecimal max = null;
    BigDecimal min = null;
    int maxIdx = -1;
    for (int i = 0; i < outcomePrices.size(); i++) {
      String raw = outcomePrices.get(i);
      if (raw == null || raw.isBlank()) {
        return null;
      }
      BigDecimal p;
      try {
        p = new BigDecimal(raw.trim());
      } catch (Exception e) {
        return null;
      }
      if (max == null || p.compareTo(max) > 0) {
        max = p;
        maxIdx = i;
      }
      if (min == null || p.compareTo(min) < 0) {
        min = p;
      }
    }
    if (max == null || min == null || maxIdx < 0) {
      return null;
    }
    if (max.compareTo(new BigDecimal("0.999")) < 0) {
      return null;
    }
    if (min.compareTo(new BigDecimal("0.001")) > 0) {
      return null;
    }
    return outcomes.get(maxIdx);
  }

  private static Map<String, Object> topOfBook(JsonNode book) {
    JsonNode bids = book.path("bids");
    if (!bids.isArray()) {
      bids = book.path("buys");
    }
    JsonNode asks = book.path("asks");
    if (!asks.isArray()) {
      asks = book.path("sells");
    }
    JsonNode bestBid = bestBidLevel(bids);
    JsonNode bestAsk = bestAskLevel(asks);

    BigDecimal bid = priceOrNull(bestBid);
    BigDecimal ask = priceOrNull(bestAsk);
    BigDecimal mid = bid != null && ask != null ? bid.add(ask).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP) : null;
    BigDecimal spread = bid != null && ask != null ? ask.subtract(bid) : null;

    // Extract full book levels (top 10)
    List<Map<String, Object>> bidLevels = extractLevels(bids, 10, true);
    List<Map<String, Object>> askLevels = extractLevels(asks, 10, false);

    // Calculate total volumes
    double totalBidVolume = bidLevels.stream().mapToDouble(l -> (Double) l.get("size")).sum();
    double totalAskVolume = askLevels.stream().mapToDouble(l -> (Double) l.get("size")).sum();

    // Calculate book imbalance: (bids - asks) / (bids + asks)
    double totalVolume = totalBidVolume + totalAskVolume;
    Double bookImbalance = totalVolume > 0 ? (totalBidVolume - totalAskVolume) / totalVolume : null;

    // Calculate depth within 1% of mid
    Double depthAt1Pct = null;
    if (mid != null && mid.doubleValue() > 0) {
      depthAt1Pct = calculateDepthWithinPercent(bidLevels, askLevels, mid.doubleValue(), 0.01);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("assetId", textOrNull(book.path("asset_id")));
    out.put("timestamp", textOrNull(book.path("timestamp")));
    out.put("hash", textOrNull(book.path("hash")));
    out.put("bestBid", bestBid);
    out.put("bestAsk", bestAsk);
    out.put("mid", mid == null ? null : mid.toPlainString());
    out.put("spread", spread == null ? null : spread.toPlainString());

    // NEW: Full order book depth
    out.put("bidLevels", bidLevels);
    out.put("askLevels", askLevels);
    out.put("totalBidVolume", totalBidVolume);
    out.put("totalAskVolume", totalAskVolume);
    out.put("bookImbalance", bookImbalance);
    out.put("depthAt1Pct", depthAt1Pct);

    return out;
  }

  private static List<Map<String, Object>> extractLevels(JsonNode levels, int limit, boolean descending) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (levels == null || !levels.isArray()) {
      return result;
    }

    // Convert to list and sort
    List<JsonNode> levelList = new ArrayList<>();
    for (JsonNode level : levels) {
      levelList.add(level);
    }

    // Sort by price (descending for bids, ascending for asks)
    levelList.sort((a, b) -> {
      BigDecimal pa = priceOrNull(a);
      BigDecimal pb = priceOrNull(b);
      if (pa == null && pb == null) return 0;
      if (pa == null) return 1;
      if (pb == null) return -1;
      return descending ? pb.compareTo(pa) : pa.compareTo(pb);
    });

    // Extract top N levels
    int count = Math.min(limit, levelList.size());
    for (int i = 0; i < count; i++) {
      JsonNode level = levelList.get(i);
      BigDecimal price = priceOrNull(level);
      BigDecimal size = sizeOrNull(level);
      if (price != null && size != null && price.doubleValue() > 0 && size.doubleValue() > 0) {
        result.add(Map.of(
            "price", price.doubleValue(),
            "size", size.doubleValue()
        ));
      }
    }

    return result;
  }

  private static Double calculateDepthWithinPercent(
      List<Map<String, Object>> bidLevels,
      List<Map<String, Object>> askLevels,
      double midPrice,
      double percentRange
  ) {
    if (midPrice <= 0) return null;

    double lowerBound = midPrice * (1 - percentRange);
    double upperBound = midPrice * (1 + percentRange);
    double depth = 0;

    for (Map<String, Object> level : bidLevels) {
      double price = (Double) level.get("price");
      if (price >= lowerBound) {
        depth += (Double) level.get("size");
      }
    }

    for (Map<String, Object> level : askLevels) {
      double price = (Double) level.get("price");
      if (price <= upperBound) {
        depth += (Double) level.get("size");
      }
    }

    return depth;
  }

  private static BigDecimal sizeOrNull(JsonNode level) {
    if (level == null) return null;
    String sizeStr = level.path("size").asText(null);
    if (sizeStr == null || sizeStr.isBlank()) return null;
    try {
      return new BigDecimal(sizeStr);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static JsonNode bestBidLevel(JsonNode bids) {
    if (bids == null || !bids.isArray() || bids.isEmpty()) {
      return null;
    }
    JsonNode best = null;
    BigDecimal bestPrice = null;
    for (JsonNode level : bids) {
      BigDecimal p = priceOrNull(level);
      if (p == null) {
        continue;
      }
      if (bestPrice == null || p.compareTo(bestPrice) > 0) {
        bestPrice = p;
        best = level;
      }
    }
    return best;
  }

  private static JsonNode bestAskLevel(JsonNode asks) {
    if (asks == null || !asks.isArray() || asks.isEmpty()) {
      return null;
    }
    JsonNode best = null;
    BigDecimal bestPrice = null;
    for (JsonNode level : asks) {
      BigDecimal p = priceOrNull(level);
      if (p == null) {
        continue;
      }
      if (bestPrice == null || p.compareTo(bestPrice) < 0) {
        bestPrice = p;
        best = level;
      }
    }
    return best;
  }

  private static BigDecimal priceOrNull(JsonNode level) {
    if (level == null || level.isNull() || level.isMissingNode()) {
      return null;
    }
    String raw = level.path("price").asText(null);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private void enforceMarketLimitLocked() {
    int max = properties.marketContext().maxTrackedMarkets();
    while (markets.size() >= max && !markets.isEmpty()) {
      Iterator<Map.Entry<String, MarketState>> it = markets.entrySet().iterator();
      if (!it.hasNext()) {
        break;
      }
      Map.Entry<String, MarketState> eldest = it.next();
      it.remove();
      log.info("market-context evicted market slug={} reason=maxTrackedMarkets", eldest.getKey());
    }
  }

  private List<String> parseJsonStringArray(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (node.isArray()) {
      return asStringList(node);
    }
    if (node.isTextual()) {
      String raw = node.asText("").trim();
      if (raw.isEmpty()) {
        return List.of();
      }
      try {
        JsonNode parsed = objectMapper.readTree(raw);
        if (parsed != null && parsed.isArray()) {
          return asStringList(parsed);
        }
      } catch (Exception ignored) {
      }
    }
    return List.of();
  }

  private static List<String> asStringList(JsonNode array) {
    if (array == null || !array.isArray() || array.isEmpty()) {
      return List.of();
    }
    var out = new java.util.ArrayList<String>(array.size());
    for (JsonNode n : array) {
      String v = n == null ? null : n.asText(null);
      if (v != null && !v.isBlank()) {
        out.add(v.trim());
      }
    }
    return out;
  }

  private static Instant parseInstantOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Boolean boolOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isTextual()) {
      String raw = node.asText("").trim().toLowerCase();
      if ("true".equals(raw)) {
        return true;
      }
      if ("false".equals(raw)) {
        return false;
      }
    }
    return null;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String v = node.asText(null);
    if (v == null || v.isBlank()) {
      return null;
    }
    return v.trim();
  }

  private static String buildTradeKey(String slug, String transactionHash, String asset, String side, long tsSeconds) {
    String tx = transactionHash == null ? "" : transactionHash.trim();
    String a = asset == null ? "" : asset.trim();
    String s = side == null ? "" : side.trim();
    if (!tx.isEmpty()) {
      return "%s:%s:%s".formatted(tx, a, s);
    }
    return "%s:%d:%s:%s".formatted(slug, tsSeconds, a, s);
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public long publishedGammaSnapshots() {
    return publishedGammaSnapshots.get();
  }

  public long publishedClobTobs() {
    return publishedClobTobs.get();
  }

  public long publishedMarketTrades() {
    return publishedMarketTrades.get();
  }

  public long failures() {
    return failures.get();
  }

  public long gammaPolls() {
    return gammaPolls.get();
  }

  public long lastGammaPollAtMillis() {
    return lastGammaPollAtMillis;
  }

  public int trackedMarkets() {
    synchronized (marketLock) {
      return markets.size();
    }
  }

  private static final class MarketState {
    private final String slug;
    private final long firstSeenAtMillis;
    private volatile long lastSeenAtMillis;

    private volatile long lastGammaFetchAtMillis;
    private volatile long lastMarketTradesFetchAtMillis;
    private volatile GammaMarket gamma;
    private volatile String lastGammaSignature = "";
    private final Map<String, CachedTob> tobByTokenId = new LinkedHashMap<>();

    private MarketState(String slug, long nowMillis) {
      this.slug = slug;
      this.firstSeenAtMillis = nowMillis;
      this.lastSeenAtMillis = nowMillis;
    }
  }

  private record CachedTob(long fetchedAtMillis, Map<String, Object> tob) {
  }

  private record GammaMarket(
      String slug,
      String eventId,
      String eventSlug,
      String eventTitle,
      String marketId,
      String conditionId,
      Instant endDate,
      Boolean closed,
      Boolean resolved,
      String resolution,
      String umaResolutionStatus,
      String updatedAt,
      List<String> clobTokenIds,
      List<String> outcomes,
      List<String> outcomePrices,
      JsonNode rawEvent,
      JsonNode rawMarket
  ) {
  }
}
