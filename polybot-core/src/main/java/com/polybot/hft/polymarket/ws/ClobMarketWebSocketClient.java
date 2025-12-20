package com.polybot.hft.polymarket.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.events.HftEventsProperties;
import com.polybot.hft.events.payload.MarketTopOfBookEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ClobMarketWebSocketClient {

  private static final long HEARTBEAT_LOG_INTERVAL_SECONDS = 15L;
  private static final long FRESH_TOB_LOG_THRESHOLD_MILLIS = 5_000L;

  private final @NonNull HftProperties properties;
  private final @NonNull HttpClient httpClient;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;
  private final @NonNull HftEventsProperties eventsProperties;
  private final @NonNull HftEventPublisher events;

  private final Map<String, TopOfBook> topOfBookByAssetId = new ConcurrentHashMap<>();
  private final Set<String> subscribedAssetIds = ConcurrentHashMap.newKeySet();
  private final Map<String, AtomicLong> lastTobEventAtMillisByAssetId = new ConcurrentHashMap<>();

  private final AtomicLong messagesReceived = new AtomicLong(0);
  private final AtomicLong bookMessages = new AtomicLong(0);
  private final AtomicLong priceChangeMessages = new AtomicLong(0);
  private final AtomicLong lastTradeMessages = new AtomicLong(0);
  private final AtomicLong lastMessageAtMillis = new AtomicLong(0);
  private final AtomicLong lastReconnectAttemptAtMillis = new AtomicLong(0);
  private final AtomicBoolean maintenanceScheduled = new AtomicBoolean(false);

  private final ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "clob-ws-ping");
    t.setDaemon(true);
    return t;
  });

  private volatile WebSocket webSocket;
  @Getter
  private volatile boolean started = false;

  private static URI buildMarketWsUri(String baseWsUrl) {
    String base = baseWsUrl.endsWith("/") ? baseWsUrl.substring(0, baseWsUrl.length() - 1) : baseWsUrl;
    return URI.create(base + "/ws/market");
  }

  private static BigDecimal extractBestPrice(JsonNode levels, boolean bestIsMax) {
    if (levels == null || !levels.isArray()) {
      return null;
    }
    BigDecimal best = null;
    for (JsonNode level : levels) {
      BigDecimal p = parseDecimal(level.path("price").asText(null));
      if (p == null) {
        continue;
      }
      if (best == null) {
        best = p;
      } else if (bestIsMax && p.compareTo(best) > 0) {
        best = p;
      } else if (!bestIsMax && p.compareTo(best) < 0) {
        best = p;
      }
    }
    return best;
  }

  private static TopLevel extractBestLevel(JsonNode levels, boolean bestIsMax) {
    if (levels == null || !levels.isArray()) {
      return null;
    }
    BigDecimal bestPrice = null;
    BigDecimal bestSize = null;
    for (JsonNode level : levels) {
      BigDecimal price = parseDecimal(level.path("price").asText(null));
      if (price == null) {
        continue;
      }
      BigDecimal size = parseDecimal(level.path("size").asText(null));
      if (bestPrice == null) {
        bestPrice = price;
        bestSize = size;
        continue;
      }
      int cmp = price.compareTo(bestPrice);
      boolean better = bestIsMax ? (cmp > 0) : (cmp < 0);
      if (better) {
        bestPrice = price;
        bestSize = size;
      }
    }
    if (bestPrice == null) {
      return null;
    }
    return new TopLevel(bestPrice, bestSize);
  }

  private static BigDecimal parseDecimal(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return new BigDecimal(s);
  }

  private static String sampleAssetSuffixes(List<String> assetIds, int max) {
    if (assetIds == null || assetIds.isEmpty() || max <= 0) {
      return "[]";
    }
    return assetIds.stream().limit(max).map(ClobMarketWebSocketClient::suffix).collect(Collectors.joining(", ", "[", assetIds.size() > max ? ", ...]" : "]"));
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

  private static List<String> sanitize(List<String> assetIds) {
    if (assetIds == null || assetIds.isEmpty()) {
      return List.of();
    }
    return assetIds.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().collect(Collectors.toList());
  }

  public Optional<TopOfBook> getTopOfBook(String assetId) {
    return Optional.ofNullable(topOfBookByAssetId.get(assetId));
  }

  public int subscribedAssetCount() {
    return subscribedAssetIds.size();
  }

  public int topOfBookCount() {
    return topOfBookByAssetId.size();
  }

  @PostConstruct
  void startIfEnabled() {
    HftProperties.Polymarket polymarket = properties.polymarket();
    if (!polymarket.marketWsEnabled()) {
      return;
    }
    maybeLoadCacheFromDisk();
    List<String> assets = polymarket.marketAssetIds();
    if (assets != null && !assets.isEmpty()) {
      subscribeAssets(assets);
      return;
    }
    if (!subscribedAssetIds.isEmpty()) {
      log.info("Market WS enabled; warm-started with {} cached subscriptions.", subscribedAssetIds.size());
      return;
    }
    log.info("Market WS enabled; waiting for market asset subscriptions.");
  }

  public void subscribeAssets(List<String> assetIds) {
    if (!properties.polymarket().marketWsEnabled()) {
      return;
    }
    List<String> sanitized = sanitize(assetIds);
    if (sanitized.isEmpty()) {
      return;
    }

    synchronized (this) {
      boolean changed = subscribedAssetIds.addAll(sanitized);
      if (!started) {
        connectLocked();
        return;
      }
      if (changed) {
        reconnectLocked();
      }
    }
  }

  /**
   * Replace the current subscription set (prunes old assets).
   *
   * Important for WS decision-time coverage: add-only subscriptions accumulate expired markets over time,
   * which bloats caches and makes "fresh TOB" effectively unattainable for the active universe.
   */
  public void setSubscribedAssets(List<String> assetIds) {
    if (!properties.polymarket().marketWsEnabled()) {
      return;
    }
    List<String> sanitized = sanitize(assetIds);
    if (sanitized.isEmpty()) {
      return;
    }

    Set<String> desired = new HashSet<>(sanitized);
    synchronized (this) {
      if (subscribedAssetIds.equals(desired)) {
        return;
      }

      subscribedAssetIds.clear();
      subscribedAssetIds.addAll(desired);

      // Prune stale caches so we don't persist/heartbeat thousands of dead markets.
      topOfBookByAssetId.keySet().retainAll(desired);
      lastTobEventAtMillisByAssetId.keySet().retainAll(desired);

      if (!started) {
        connectLocked();
        return;
      }
      reconnectLocked();
    }
  }

  private void reconnectLocked() {
    WebSocket ws = this.webSocket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "resubscribe").join();
      } catch (Exception ignored) {
      }
    }
    this.webSocket = null;
    started = false;
    lastMessageAtMillis.set(0);
    lastTobEventAtMillisByAssetId.clear();
    connectLocked();
  }

  private void connectLocked() {
    if (started) {
      return;
    }
    started = true;

    URI wsUri = buildMarketWsUri(properties.polymarket().clobWsUrl());
    log.info("Connecting to CLOB market websocket: {}", wsUri);

    CompletableFuture<WebSocket> cf = httpClient.newWebSocketBuilder().buildAsync(wsUri, new Listener());
    this.webSocket = cf.join();

    if (maintenanceScheduled.compareAndSet(false, true)) {
      pingExecutor.scheduleAtFixedRate(() -> {
        WebSocket ws = this.webSocket;
        if (ws != null) {
          try {
            ws.sendPing(ByteBuffer.wrap(new byte[]{1}));
          } catch (Exception ignored) {
          }
        }
      }, 10, 10, TimeUnit.SECONDS);

      pingExecutor.scheduleAtFixedRate(this::logHeartbeat, HEARTBEAT_LOG_INTERVAL_SECONDS, HEARTBEAT_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
      pingExecutor.scheduleAtFixedRate(this::maintainConnectionSafely, 5, 5, TimeUnit.SECONDS);

      long snapshotMillis = eventsProperties.marketWsSnapshotPublishMillis();
      if (snapshotMillis > 0) {
        pingExecutor.scheduleAtFixedRate(this::republishSnapshotsSafely, snapshotMillis, snapshotMillis, TimeUnit.MILLISECONDS);
      }

      long flushMillis = properties.polymarket().marketWsCacheFlushMillis();
      if (flushMillis > 0 && isCachePersistenceEnabled()) {
        pingExecutor.scheduleAtFixedRate(this::flushCacheSafely, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
      }
    }
  }

  @PreDestroy
  void shutdown() {
    flushCacheSafely();
    WebSocket ws = this.webSocket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
      } catch (Exception ignored) {
      }
    }
    pingExecutor.shutdownNow();
  }

  private String buildSubscribeMessage(List<String> assetIds) {
    try {
      return objectMapper.writeValueAsString(Map.of("assets_ids", assetIds, "type", "market"));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to build market ws subscribe message", e);
    }
  }

  private void sendSubscribeLocked() {
    WebSocket ws = this.webSocket;
    if (ws == null) {
      return;
    }
    List<String> snapshot = subscribedAssetIds.stream().sorted().toList();
    if (snapshot.isEmpty()) {
      return;
    }
    ws.sendText(buildSubscribeMessage(snapshot), true);
    log.info("Subscribed to {} market assets via WS (e.g. {})", snapshot.size(), sampleAssetSuffixes(snapshot, 4));
  }

  private void handleMessage(String message) {
    if ("PONG".equalsIgnoreCase(message) || "PING".equalsIgnoreCase(message)) {
      lastMessageAtMillis.set(System.currentTimeMillis());
      return;
    }
    messagesReceived.incrementAndGet();
    lastMessageAtMillis.set(System.currentTimeMillis());
    try {
      JsonNode node = objectMapper.readTree(message);
      handleMessageNode(node);
    } catch (Exception e) {
      log.debug("Failed to parse ws message: {}", message);
    }
  }

  private void handleMessageNode(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode n : node) {
        handleMessageNode(n);
      }
      return;
    }

    String eventType = node.path("event_type").asText("");
    switch (eventType) {
      case "book" -> {
        bookMessages.incrementAndGet();
        handleBook(node);
      }
      case "price_change" -> {
        priceChangeMessages.incrementAndGet();
        handlePriceChange(node);
      }
      case "last_trade_price" -> {
        lastTradeMessages.incrementAndGet();
        handleLastTradePrice(node);
      }
      default -> {
      }
    }
  }

  private void maintainConnectionSafely() {
    try {
      maintainConnection();
    } catch (Exception e) {
      log.debug("Market WS maintenance failed: {}", e.toString());
    }
  }

  private void maintainConnection() {
    if (!properties.polymarket().marketWsEnabled()) {
      return;
    }
    if (subscribedAssetIds.isEmpty()) {
      return;
    }

    long staleTimeoutMillis = properties.polymarket().marketWsStaleTimeoutMillis();
    long reconnectBackoffMillis = properties.polymarket().marketWsReconnectBackoffMillis();
    long now = System.currentTimeMillis();

    boolean disconnected = !started || webSocket == null;
    long lastAt = lastMessageAtMillis.get();
    boolean stale = staleTimeoutMillis > 0 && lastAt > 0 && (now - lastAt) > staleTimeoutMillis;
    if (!disconnected && !stale) {
      return;
    }

    long prevAttempt = lastReconnectAttemptAtMillis.get();
    if (reconnectBackoffMillis > 0 && prevAttempt > 0 && now - prevAttempt < reconnectBackoffMillis) {
      return;
    }
    lastReconnectAttemptAtMillis.set(now);

    String reason = disconnected ? "disconnected" : "stale";
    long lastAgo = lastAt <= 0 ? -1 : now - lastAt;
    log.warn("Market WS reconnecting (reason={}, subscribed={}, tobKnown={}, lastMsgAgoMs={})", reason, subscribedAssetIds.size(), topOfBookByAssetId.size(), lastAgo);

    synchronized (this) {
      if (subscribedAssetIds.isEmpty()) {
        return;
      }
      reconnectLocked();
    }
  }

  private void logHeartbeat() {
    if (!started) {
      return;
    }
    long now = System.currentTimeMillis();
    long lastAt = lastMessageAtMillis.get();
    String lastAgo = lastAt <= 0 ? "never" : (now - lastAt) + "ms ago";

    int subscribed = subscribedAssetIds.size();
    int known = topOfBookByAssetId.size();
    int fresh = 0;
    for (String assetId : subscribedAssetIds) {
      TopOfBook tob = topOfBookByAssetId.get(assetId);
      if (tob == null || tob.updatedAt() == null) {
        continue;
      }
      if (now - tob.updatedAt().toEpochMilli() <= FRESH_TOB_LOG_THRESHOLD_MILLIS) {
        fresh++;
      }
    }
    log.info("Market WS heartbeat subscribed={} tobKnown={} tobFresh({}ms)={} msgs={} book={} priceChange={} lastTrade={} lastMsg={}",
        subscribed, known, FRESH_TOB_LOG_THRESHOLD_MILLIS, fresh, messagesReceived.get(), bookMessages.get(), priceChangeMessages.get(), lastTradeMessages.get(), lastAgo);
  }

  private void republishSnapshotsSafely() {
    try {
      republishSnapshots();
    } catch (Exception e) {
      log.debug("Market WS snapshot republish failed: {}", e.toString());
    }
  }

  private void republishSnapshots() {
    if (!started) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }
    if (subscribedAssetIds.isEmpty() || topOfBookByAssetId.isEmpty()) {
      return;
    }

    long staleTimeoutMillis = properties.polymarket().marketWsStaleTimeoutMillis();
    long nowMillis = System.currentTimeMillis();
    long lastAt = lastMessageAtMillis.get();
    boolean stale = staleTimeoutMillis > 0 && lastAt > 0 && (nowMillis - lastAt) > staleTimeoutMillis;
    if (stale) {
      return;
    }

    Instant now = Instant.now(clock);
    for (String assetId : subscribedAssetIds) {
      TopOfBook tob = topOfBookByAssetId.get(assetId);
      if (tob == null || tob.bestBid() == null || tob.bestAsk() == null) {
        continue;
      }

      TopOfBook snapshot = new TopOfBook(
          tob.bestBid(),
          tob.bestAsk(),
          tob.bestBidSize(),
          tob.bestAskSize(),
          tob.lastTradePrice(),
          now,
          tob.lastTradeAt()
      );
      topOfBookByAssetId.put(assetId, snapshot);
      maybePublishTopOfBook(assetId, snapshot);
    }
  }

  private void handleBook(JsonNode node) {
    String assetId = node.path("asset_id").asText(null);
    if (assetId == null) {
      return;
    }
    JsonNode bidsNode = node.has("bids") ? node.get("bids") : node.get("buys");
    JsonNode asksNode = node.has("asks") ? node.get("asks") : node.get("sells");

    TopLevel bestBidLevel = extractBestLevel(bidsNode, true);
    TopLevel bestAskLevel = extractBestLevel(asksNode, false);

    BigDecimal bestBid = bestBidLevel == null ? null : bestBidLevel.price();
    BigDecimal bestBidSize = bestBidLevel == null ? null : bestBidLevel.size();
    BigDecimal bestAsk = bestAskLevel == null ? null : bestAskLevel.price();
    BigDecimal bestAskSize = bestAskLevel == null ? null : bestAskLevel.size();
    BigDecimal lastTradePrice = parseDecimal(node.path("last_trade_price").asText(null));

    Instant now = Instant.now(clock);
    TopOfBook tob = topOfBookByAssetId.compute(assetId, (k, prev) -> {
      BigDecimal prevLast = prev == null ? null : prev.lastTradePrice();
      Instant prevTradeAt = prev == null ? null : prev.lastTradeAt();

      BigDecimal nextLast = lastTradePrice != null ? lastTradePrice : prevLast;
      Instant nextTradeAt = prevTradeAt;
      if (nextLast != null && (prevLast == null || (lastTradePrice != null && prevLast != null && lastTradePrice.compareTo(prevLast) != 0))) {
        nextTradeAt = now;
      }
      BigDecimal nextBidSize = bestBidSize != null ? bestBidSize : (prev == null ? null : prev.bestBidSize());
      BigDecimal nextAskSize = bestAskSize != null ? bestAskSize : (prev == null ? null : prev.bestAskSize());
      return new TopOfBook(bestBid, bestAsk, nextBidSize, nextAskSize, nextLast, now, nextTradeAt);
    });
    maybePublishTopOfBook(assetId, tob);
  }

  private void handlePriceChange(JsonNode node) {
    JsonNode changes = node.path("price_changes");
    if (!changes.isArray()) {
      return;
    }
    Instant now = Instant.now(clock);
    for (JsonNode change : changes) {
      String assetId = change.path("asset_id").asText(null);
      if (assetId == null) {
        continue;
      }
      BigDecimal bestBid = parseDecimal(change.path("best_bid").asText(null));
      BigDecimal bestAsk = parseDecimal(change.path("best_ask").asText(null));
      BigDecimal bestBidSize = parseDecimal(change.path("best_bid_size").asText(null));
      BigDecimal bestAskSize = parseDecimal(change.path("best_ask_size").asText(null));

      TopOfBook tob = topOfBookByAssetId.compute(assetId, (k, prev) -> new TopOfBook(
          bestBid != null ? bestBid : (prev == null ? null : prev.bestBid()),
          bestAsk != null ? bestAsk : (prev == null ? null : prev.bestAsk()),
          bestBidSize != null ? bestBidSize : (prev == null ? null : prev.bestBidSize()),
          bestAskSize != null ? bestAskSize : (prev == null ? null : prev.bestAskSize()),
          prev == null ? null : prev.lastTradePrice(),
          now,
          prev == null ? null : prev.lastTradeAt()
      ));
      maybePublishTopOfBook(assetId, tob);
    }
  }

  private void handleLastTradePrice(JsonNode node) {
    String assetId = node.path("asset_id").asText(null);
    if (assetId == null) {
      return;
    }
    BigDecimal price = parseDecimal(node.path("price").asText(null));
    Instant now = Instant.now(clock);
    TopOfBook tob = topOfBookByAssetId.compute(assetId, (k, prev) -> new TopOfBook(
        prev == null ? null : prev.bestBid(),
        prev == null ? null : prev.bestAsk(),
        prev == null ? null : prev.bestBidSize(),
        prev == null ? null : prev.bestAskSize(),
        price,
        now,
        now
    ));
    maybePublishTopOfBook(assetId, tob);
  }

  private void maybePublishTopOfBook(String assetId, TopOfBook tob) {
    if (assetId == null || assetId.isBlank() || tob == null) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }

    long minIntervalMillis = eventsProperties.marketWsTobMinIntervalMillis();
    long nowMillis = tob.updatedAt() != null ? tob.updatedAt().toEpochMilli() : System.currentTimeMillis();
    if (minIntervalMillis > 0) {
      AtomicLong last = lastTobEventAtMillisByAssetId.computeIfAbsent(assetId, k -> new AtomicLong(0L));
      while (true) {
        long prev = last.get();
        if (nowMillis - prev < minIntervalMillis) {
          return;
        }
        if (last.compareAndSet(prev, nowMillis)) {
          break;
        }
      }
    }

    events.publish(tob.updatedAt(), HftEventTypes.MARKET_WS_TOB, assetId, new MarketTopOfBookEvent(
        assetId,
        tob.bestBid(),
        tob.bestBidSize(),
        tob.bestAsk(),
        tob.bestAskSize(),
        tob.lastTradePrice(),
        tob.updatedAt(),
        tob.lastTradeAt()
    ));
  }

  private record TopLevel(BigDecimal price, BigDecimal size) {}

  private boolean isCachePersistenceEnabled() {
    String path = properties.polymarket().marketWsCachePath();
    return path != null && !path.isBlank();
  }

  private void maybeLoadCacheFromDisk() {
    if (!isCachePersistenceEnabled()) {
      return;
    }
    String pathStr = properties.polymarket().marketWsCachePath();
    Path path;
    try {
      path = Path.of(pathStr);
    } catch (Exception e) {
      log.warn("Market WS cache path is invalid: {}", pathStr);
      return;
    }
    if (!Files.exists(path)) {
      return;
    }
    try {
      String json = Files.readString(path);
      if (json == null || json.isBlank()) {
        return;
      }
      MarketWsCacheSnapshot snapshot = objectMapper.readValue(json, MarketWsCacheSnapshot.class);
      if (snapshot == null || snapshot.topOfBookByAssetId() == null || snapshot.topOfBookByAssetId().isEmpty()) {
        return;
      }
      topOfBookByAssetId.putAll(snapshot.topOfBookByAssetId());
      log.info("Loaded market WS TOB cache from {} (assets={})", pathStr, snapshot.topOfBookByAssetId().size());

      if (Boolean.TRUE.equals(eventsProperties.marketWsCachePublishOnStart()) && events.isEnabled()) {
        for (Map.Entry<String, TopOfBook> e : snapshot.topOfBookByAssetId().entrySet()) {
          if (e == null) {
            continue;
          }
          String assetId = e.getKey();
          TopOfBook tob = e.getValue();
          if (assetId == null || assetId.isBlank() || tob == null) {
            continue;
          }
          maybePublishTopOfBook(assetId, tob);
        }
        log.info("Republished {} cached market WS TOBs on startup", snapshot.topOfBookByAssetId().size());
      }

      // Optional warm-start: if no asset list was configured, reuse the last subscribed set to connect immediately.
      if (properties.polymarket().marketAssetIds().isEmpty()
          && snapshot.subscribedAssetIds() != null
          && !snapshot.subscribedAssetIds().isEmpty()
          && subscribedAssetIds.isEmpty()) {
        setSubscribedAssets(snapshot.subscribedAssetIds());
      }
    } catch (Exception e) {
      log.warn("Failed to load market WS TOB cache from {}: {}", pathStr, e.getMessage());
    }
  }

  private void flushCacheSafely() {
    if (!isCachePersistenceEnabled()) {
      return;
    }
    try {
      flushCacheToDisk();
    } catch (Exception e) {
      log.debug("Market WS cache flush failed: {}", e.getMessage());
    }
  }

  private void flushCacheToDisk() throws Exception {
    String pathStr = properties.polymarket().marketWsCachePath();
    if (pathStr == null || pathStr.isBlank()) {
      return;
    }

    Path path = Path.of(pathStr);
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    MarketWsCacheSnapshot snapshot = new MarketWsCacheSnapshot(
        Instant.now(clock),
        subscribedAssetIds.stream().sorted().toList(),
        new HashMap<>(topOfBookByAssetId)
    );

    String json = objectMapper.writeValueAsString(snapshot);
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    try {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private record MarketWsCacheSnapshot(
      Instant snapshotAt,
      List<String> subscribedAssetIds,
      Map<String, TopOfBook> topOfBookByAssetId
  ) {}

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder buf = new StringBuilder(8192);

    private Listener() {
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      log.info("CLOB market websocket opened");
      ClobMarketWebSocketClient.this.webSocket = webSocket;
      lastMessageAtMillis.set(System.currentTimeMillis());
      sendSubscribeLocked();
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      buf.append(data);
      if (last) {
        String message = buf.toString();
        buf.setLength(0);
        handleMessage(message);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
      lastMessageAtMillis.set(System.currentTimeMillis());
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      log.warn("CLOB market websocket closed (status={}, reason={})", statusCode, reason);
      if (ClobMarketWebSocketClient.this.webSocket == webSocket) {
        ClobMarketWebSocketClient.this.webSocket = null;
        started = false;
      }
      return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      log.warn("CLOB market websocket error: {}", error.toString());
      if (ClobMarketWebSocketClient.this.webSocket == webSocket) {
        ClobMarketWebSocketClient.this.webSocket = null;
        started = false;
      }
    }
  }
}
