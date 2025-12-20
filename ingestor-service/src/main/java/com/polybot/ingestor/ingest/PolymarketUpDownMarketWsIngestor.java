package com.polybot.ingestor.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.ingestor.config.MarketWsProperties;
import com.polybot.ingestor.polymarket.PolymarketGammaApiClient;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.hft.polymarket.ws.TopOfBook;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Discovers the current BTC/ETH Up/Down 15m + 1h markets and subscribes their tokenIds to the
 * Polymarket market websocket, so we can collect low-latency top-of-book snapshots.
 *
 * This is the missing piece to classify maker/taker accurately: our current TOB snapshots are
 * trade-triggered and ~60–100s stale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketUpDownMarketWsIngestor {

  private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
  private static final long FRESH_TOB_MAX_AGE_MILLIS = 5_000L;

  private final @NonNull MarketWsProperties properties;
  private final @NonNull HftProperties hft;
  private final @NonNull PolymarketGammaApiClient gammaApi;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull HftEventPublisher events;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;

  private final AtomicLong cycles = new AtomicLong(0);
  private final AtomicLong marketsDiscovered = new AtomicLong(0);
  private final AtomicLong slugFetchFailures = new AtomicLong(0);

  @Scheduled(
      initialDelayString = "5000",
      fixedDelayString = "${ingestor.market-ws.discovery-interval-seconds:30}000"
  )
  public void refreshSubscriptions() {
    if (!properties.enabled()) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }
    if (!Boolean.TRUE.equals(hft.polymarket().marketWsEnabled())) {
      return;
    }

    cycles.incrementAndGet();
    Instant now = Instant.now(clock);

    List<String> candidates = buildCandidateSlugs(now);
    if (candidates.isEmpty()) {
      return;
    }

    List<DiscoveredMarket> markets = new ArrayList<>(candidates.size());
    Set<String> seen = new HashSet<>();
    for (String slug : candidates) {
      if (slug == null || slug.isBlank() || !seen.add(slug)) {
        continue;
      }
      try {
        DiscoveredMarket market = fetchMarket(slug);
        if (market != null && isOpen(now, market)) {
          markets.add(market);
        }
      } catch (Exception e) {
        slugFetchFailures.incrementAndGet();
        log.debug("market-ws gamma fetch failed slug={} err={}", slug, e.toString());
      }
      sleep(properties.requestDelayMillis());
    }

    marketsDiscovered.addAndGet(markets.size());
    if (markets.isEmpty()) {
      return;
    }

    List<String> assetIds = markets.stream()
        .flatMap(m -> List.of(m.upTokenId(), m.downTokenId()).stream())
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .distinct()
        .toList();
    marketWs.setSubscribedAssets(assetIds);

    // Coverage reporting: decision-time TOB needs BOTH legs, continuously.
    int pairs = markets.size();
    int pairsWithBoth = 0;
    int pairsFreshBoth = 0;
    int pairsStale = 0;
    for (DiscoveredMarket m : markets) {
      TopOfBook up = marketWs.getTopOfBook(m.upTokenId()).orElse(null);
      TopOfBook down = marketWs.getTopOfBook(m.downTokenId()).orElse(null);
      if (up == null || down == null || up.updatedAt() == null || down.updatedAt() == null) {
        continue;
      }
      pairsWithBoth++;
      long upAge = Math.max(0, Duration.between(up.updatedAt(), now).toMillis());
      long downAge = Math.max(0, Duration.between(down.updatedAt(), now).toMillis());
      if (upAge <= FRESH_TOB_MAX_AGE_MILLIS && downAge <= FRESH_TOB_MAX_AGE_MILLIS) {
        pairsFreshBoth++;
      } else {
        pairsStale++;
      }
    }
    log.info("market-ws coverage markets={} pairsWithBothTob={} pairsFreshBoth({}ms)={} pairsStale={} subscribedAssets={} tobKnown={}",
        pairs, pairsWithBoth, FRESH_TOB_MAX_AGE_MILLIS, pairsFreshBoth, pairsStale,
        marketWs.subscribedAssetCount(), marketWs.topOfBookCount());

    if (log.isDebugEnabled()) {
      log.debug("market-ws subscribed markets={} assets={}", markets.size(), assetIds.size());
    }
  }

  private List<String> buildCandidateSlugs(Instant now) {
    List<String> out = new ArrayList<>();
    out.addAll(candidateUpDown15mSlugs("btc", now, properties.lookback15mMinutes(), properties.lookahead15mMinutes()));
    out.addAll(candidateUpDown15mSlugs("eth", now, properties.lookback15mMinutes(), properties.lookahead15mMinutes()));

    ZonedDateTime nowEt = ZonedDateTime.ofInstant(now, ET_ZONE);
    out.addAll(candidateUpOrDown1hSlugs("bitcoin", nowEt, properties.lookback1hHours(), properties.lookahead1hHours()));
    out.addAll(candidateUpOrDown1hSlugs("ethereum", nowEt, properties.lookback1hHours(), properties.lookahead1hHours()));
    return out;
  }

  private static List<String> candidateUpDown15mSlugs(String assetPrefix, Instant now, int lookbackMinutes, int lookaheadMinutes) {
    long nowSec = now.getEpochSecond();
    long from = nowSec - Duration.ofMinutes(Math.max(0, lookbackMinutes)).toSeconds();
    long to = nowSec + Duration.ofMinutes(Math.max(0, lookaheadMinutes)).toSeconds();

    long startFrom = (from / 900L) * 900L;
    long startTo = (to / 900L) * 900L;

    List<String> out = new ArrayList<>();
    for (long start = startFrom; start <= startTo; start += 900L) {
      out.add(assetPrefix + "-updown-15m-" + start);
    }
    return out;
  }

  private static List<String> candidateUpOrDown1hSlugs(String assetPrefix, ZonedDateTime nowEt, int lookbackHours, int lookaheadHours) {
    ZonedDateTime hourStart = nowEt.truncatedTo(ChronoUnit.HOURS);
    int back = Math.max(0, lookbackHours);
    int ahead = Math.max(0, lookaheadHours);

    List<String> out = new ArrayList<>();
    for (int i = back; i >= 1; i--) {
      out.add(buildUpOrDown1hSlug(assetPrefix, hourStart.minusHours(i)));
    }
    out.add(buildUpOrDown1hSlug(assetPrefix, hourStart));
    for (int i = 1; i <= ahead; i++) {
      out.add(buildUpOrDown1hSlug(assetPrefix, hourStart.plusHours(i)));
    }
    return out;
  }

  private static String buildUpOrDown1hSlug(String assetPrefix, ZonedDateTime hourStartEt) {
    String month = hourStartEt.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
    int day = hourStartEt.getDayOfMonth();
    int hour24 = hourStartEt.getHour();
    int hour12 = hour24 % 12;
    if (hour12 == 0) {
      hour12 = 12;
    }
    String ampm = hour24 < 12 ? "am" : "pm";
    return "%s-up-or-down-%s-%d-%d%s-et".formatted(assetPrefix, month, day, hour12, ampm);
  }

  private DiscoveredMarket fetchMarket(String slug) {
    PolymarketGammaApiClient.GammaMarketSnapshot snapshot = gammaApi.getMarketSnapshotBySlug(slug);
    if (snapshot == null) {
      return null;
    }

    JsonNode marketNode = snapshot.market();
    if (marketNode == null || marketNode.isNull() || marketNode.isMissingNode()) {
      return null;
    }

    boolean closed = marketNode.path("closed").asBoolean(false);
    if (closed) {
      return null;
    }

    String marketType;
    if (slug.contains("updown-15m")) {
      marketType = "updown-15m";
    } else if (slug.contains("up-or-down")) {
      marketType = "up-or-down";
    } else {
      return null;
    }

    Instant endTime = parseEndTime(marketNode, slug, marketType);
    if (endTime == null) {
      return null;
    }

    List<String> tokenIds = parseStringArray(marketNode.path("clobTokenIds"));
    List<String> outcomes = parseStringArray(marketNode.path("outcomes"));

    String upTokenId = null;
    String downTokenId = null;
    for (int i = 0; i < tokenIds.size() && i < outcomes.size(); i++) {
      String outcome = outcomes.get(i) == null ? "" : outcomes.get(i).trim().toLowerCase(Locale.ROOT);
      String tokenId = tokenIds.get(i);
      if (tokenId == null || tokenId.isBlank()) {
        continue;
      }
      if ("up".equals(outcome)) {
        upTokenId = tokenId.trim();
      } else if ("down".equals(outcome)) {
        downTokenId = tokenId.trim();
      }
    }

    if (upTokenId == null || downTokenId == null) {
      return null;
    }
    return new DiscoveredMarket(slug, upTokenId, downTokenId, endTime, marketType);
  }

  private static Instant parseEndTime(JsonNode marketNode, String slug, String marketType) {
    String endDateStr = marketNode.path("endDate").asText(null);
    if (endDateStr != null && !endDateStr.isBlank()) {
      try {
        return Instant.parse(endDateStr);
      } catch (Exception ignored) {
      }
    }

    if ("updown-15m".equals(marketType)) {
      int idx = slug.lastIndexOf('-');
      if (idx >= 0 && idx < slug.length() - 1) {
        try {
          long epochStart = Long.parseLong(slug.substring(idx + 1));
          return Instant.ofEpochSecond(epochStart + 900L);
        } catch (Exception ignored) {
        }
      }
    }
    return null;
  }

  private static boolean isOpen(Instant now, DiscoveredMarket m) {
    if (m == null || m.endTime() == null) {
      return false;
    }
    if (!m.endTime().isAfter(now)) {
      return false;
    }
    Duration duration = "updown-15m".equals(m.marketType()) ? Duration.ofMinutes(15) : Duration.ofHours(1);
    Instant startTime = m.endTime().minus(duration);
    return !now.isBefore(startTime);
  }

  private List<String> parseStringArray(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }

    if (node.isArray()) {
      List<String> out = new ArrayList<>(node.size());
      for (JsonNode x : node) {
        if (x == null || x.isNull() || x.isMissingNode()) {
          continue;
        }
        String v = x.asText(null);
        if (v != null && !v.isBlank()) {
          out.add(v);
        }
      }
      return out;
    }

    if (node.isTextual()) {
      String raw = node.asText(null);
      if (raw == null || raw.isBlank()) {
        return List.of();
      }
      try {
        JsonNode parsed = objectMapper.readTree(raw);
        return parseStringArray(parsed);
      } catch (Exception ignored) {
        return List.of();
      }
    }

    return List.of();
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

  private record DiscoveredMarket(
      String slug,
      String upTokenId,
      String downTokenId,
      Instant endTime,
      String marketType
  ) {
  }

  public long cycles() {
    return cycles.get();
  }

  public long marketsDiscovered() {
    return marketsDiscovered.get();
  }

  public long slugFetchFailures() {
    return slugFetchFailures.get();
  }
}
