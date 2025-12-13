package com.polybot.hft.polymarket.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.polymarket.clob.PolymarketClobClient;
import com.polybot.hft.polymarket.clob.PolymarketClobPaths;
import com.polybot.hft.polymarket.gamma.PolymarketGammaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class PolymarketMarketDiscoveryService {

  private static final int DEFAULT_CLOB_PAGE_LIMIT = 100;
  private static final int DEFAULT_CLOB_MAX_PAGES = 5;
  private static final String DEFAULT_UP_OR_DOWN_QUERY = "up or down";
  private static final String TAG_15M = "15M";
  private static final String TIMEFRAME_15M_QUERY = "15m";
  private static final int DEFAULT_GAMMA_PUBLIC_SEARCH_MAX_PAGES = 12;
  private static final int DEFAULT_GAMMA_PUBLIC_SEARCH_MAX_LIVE_MARKETS_PER_ASSET = 10;

  private final PolymarketGammaClient gammaClient;
  private final PolymarketClobClient clobClient;
  private final ObjectMapper objectMapper;

  private static String extractNextCursor(JsonNode root) {
    if (root == null || root.isNull()) {
      return null;
    }
    JsonNode v = root.get("next_cursor");
    if (v == null || v.isNull()) {
      v = root.get("nextCursor");
    }
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText(null);
    return s == null ? null : s.trim();
  }

  private static List<JsonNode> extractMarketList(JsonNode root) {
    if (root == null || root.isNull()) {
      return List.of();
    }
    if (root.isArray()) {
      return PolymarketMarketParser.extractMarkets(root);
    }
    JsonNode data = root.get("data");
    if (data != null && data.isArray()) {
      return PolymarketMarketParser.extractMarkets(data);
    }
    JsonNode markets = root.get("markets");
    if (markets != null && markets.isArray()) {
      return PolymarketMarketParser.extractMarkets(markets);
    }
    return List.of();
  }

  public List<DiscoveredMarket> searchGamma(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }

    List<DiscoveredMarket> markets = new ArrayList<>();
    try {
      JsonNode root = gammaClient.publicSearch(Map.of("q", query.trim()), Map.of());
      markets.addAll(parseMarkets("gamma-public-search", root));
    } catch (Exception e) {
      log.debug("gamma public-search failed: {}", e.toString());
    }

    if (!markets.isEmpty()) {
      return markets;
    }

    try {
      JsonNode root = gammaClient.events(Map.of("search", query.trim()), Map.of());
      markets.addAll(parseMarkets("gamma-events", root));
    } catch (Exception e) {
      log.debug("gamma events search failed: {}", e.toString());
    }

    return markets;
  }

  public List<DiscoveredMarket> searchGammaUpOrDown15mEndingSoon() {
    return searchGammaUpOrDown15mEndingSoon(List.of());
  }

  public List<DiscoveredMarket> searchGammaUpOrDown15mEndingSoon(List<String> assetQueries) {
    List<String> assets = assetQueries == null ? List.of() : assetQueries.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();

    if (assets.isEmpty()) {
      return searchGammaPublicSearchUpOrDown15m(DEFAULT_UP_OR_DOWN_QUERY + " " + TIMEFRAME_15M_QUERY, DEFAULT_GAMMA_PUBLIC_SEARCH_MAX_PAGES, Integer.MAX_VALUE);
    }

    List<DiscoveredMarket> out = new ArrayList<>();
    for (String asset : assets) {
      String query = asset + " " + DEFAULT_UP_OR_DOWN_QUERY + " " + TIMEFRAME_15M_QUERY;
      out.addAll(searchGammaPublicSearchUpOrDown15m(query, DEFAULT_GAMMA_PUBLIC_SEARCH_MAX_PAGES, DEFAULT_GAMMA_PUBLIC_SEARCH_MAX_LIVE_MARKETS_PER_ASSET));
    }
    return out;
  }

  public List<DiscoveredMarket> scanClobByQuestionContains(String needle) {
    if (needle == null || needle.isBlank()) {
      return List.of();
    }
    String n = needle.trim().toLowerCase(Locale.ROOT);
    List<DiscoveredMarket> markets = new ArrayList<>();
    markets.addAll(scanClobEndpoint(PolymarketClobPaths.SAMPLING_MARKETS, n));
    markets.addAll(scanClobEndpoint(PolymarketClobPaths.MARKETS, n));
    return markets;
  }

  private List<DiscoveredMarket> scanClobEndpoint(String path, String needleLower) {
    String cursor = null;
    List<DiscoveredMarket> out = new ArrayList<>();
    for (int page = 0; page < DEFAULT_CLOB_MAX_PAGES; page++) {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("limit", String.valueOf(DEFAULT_CLOB_PAGE_LIMIT));
      if (cursor != null && !cursor.isBlank()) {
        query.put("cursor", cursor);
        query.put("next_cursor", cursor);
      }

      JsonNode root;
      try {
        root = PolymarketClobPaths.SAMPLING_MARKETS.equals(path) ? clobClient.samplingMarkets(query) : clobClient.markets(query);
      } catch (Exception e) {
        log.debug("clob scan failed path={}: {}", path, e.toString());
        break;
      }

      String next = extractNextCursor(root);
      cursor = next != null && !next.isBlank() ? next : null;

      List<JsonNode> markets = extractMarketList(root);
      if (markets.isEmpty()) {
        break;
      }

      for (JsonNode m : markets) {
        String question = PolymarketMarketParser.question(m);
        if (question == null || question.isBlank()) {
          continue;
        }
        if (!question.toLowerCase(Locale.ROOT).contains(needleLower)) {
          continue;
        }
        if (!PolymarketMarketParser.isLive(m)) {
          continue;
        }
        Optional<YesNoTokens> tokens = PolymarketMarketParser.yesNoTokens(m, objectMapper);
        if (tokens.isEmpty()) {
          continue;
        }
        BigDecimal volume = PolymarketMarketParser.volume(m);
        out.add(new DiscoveredMarket("clob" + path, PolymarketMarketParser.id(m), PolymarketMarketParser.slug(m), question, tokens.get().yesTokenId(), tokens.get().noTokenId(), volume, PolymarketMarketParser.endEpochMillis(m)));
      }

      if (cursor == null) {
        break;
      }
    }
    return out;
  }

  private List<DiscoveredMarket> parseMarkets(String source, JsonNode root) {
    List<JsonNode> markets = PolymarketMarketParser.extractMarkets(root);
    if (markets.isEmpty()) {
      return List.of();
    }

    List<DiscoveredMarket> out = new ArrayList<>();
    for (JsonNode m : markets) {
      if (!PolymarketMarketParser.isLive(m)) {
        continue;
      }
      String question = PolymarketMarketParser.question(m);
      if (question == null || question.isBlank()) {
        continue;
      }
      Optional<YesNoTokens> tokens = PolymarketMarketParser.yesNoTokens(m, objectMapper);
      if (tokens.isEmpty()) {
        continue;
      }
      BigDecimal volume = PolymarketMarketParser.volume(m);
      out.add(new DiscoveredMarket(source, PolymarketMarketParser.id(m), PolymarketMarketParser.slug(m), question, tokens.get().yesTokenId(), tokens.get().noTokenId(), volume, PolymarketMarketParser.endEpochMillis(m)));
    }
    return out;
  }

  private List<DiscoveredMarket> searchGammaPublicSearchUpOrDown15m(String query, int maxPages, int maxLiveMarkets) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    int pages = Math.max(1, maxPages);
    int maxLive = Math.max(1, maxLiveMarkets);

    List<DiscoveredMarket> out = new ArrayList<>();
    for (int page = 1; page <= pages; page++) {
      JsonNode root;
      try {
        Map<String, String> gammaQuery = new LinkedHashMap<>();
        gammaQuery.put("q", query.trim());
        gammaQuery.put("page", String.valueOf(page));
        root = gammaClient.publicSearch(gammaQuery, Map.of());
      } catch (Exception e) {
        log.debug("gamma up-or-down 15m public-search failed page={}: {}", page, e.toString());
        break;
      }

      out.addAll(parseGammaPublicSearchTaggedEventMarkets("gamma-up-or-down-15m", root));
      if (out.size() >= maxLive) {
        break;
      }
      if (!gammaPublicSearchHasMore(root)) {
        break;
      }
    }
    return out;
  }

  private boolean gammaPublicSearchHasMore(JsonNode root) {
    if (root == null || root.isNull()) {
      return false;
    }
    JsonNode pagination = root.get("pagination");
    if (pagination == null || pagination.isNull()) {
      return false;
    }
    JsonNode hasMore = pagination.get("hasMore");
    return hasMore != null && hasMore.asBoolean(false);
  }

  private List<DiscoveredMarket> parseGammaPublicSearchTaggedEventMarkets(String source, JsonNode root) {
    if (root == null || root.isNull()) {
      return List.of();
    }
    JsonNode events = root.get("events");
    if (events == null || !events.isArray() || events.isEmpty()) {
      return List.of();
    }

    List<DiscoveredMarket> out = new ArrayList<>();
    for (JsonNode event : events) {
      if (!gammaEventHasTag(event, TAG_15M) || !gammaEventHasTag(event, DEFAULT_UP_OR_DOWN_QUERY)) {
        continue;
      }
      JsonNode markets = event.get("markets");
      if (markets == null || !markets.isArray() || markets.isEmpty()) {
        continue;
      }

      for (JsonNode m : markets) {
        if (!PolymarketMarketParser.isLive(m)) {
          continue;
        }
        String question = PolymarketMarketParser.question(m);
        if (question == null || question.isBlank()) {
          continue;
        }
        Optional<YesNoTokens> tokens = PolymarketMarketParser.yesNoTokens(m, objectMapper);
        if (tokens.isEmpty()) {
          continue;
        }
        BigDecimal volume = PolymarketMarketParser.volume(m);
        out.add(new DiscoveredMarket(source, PolymarketMarketParser.id(m), PolymarketMarketParser.slug(m), question, tokens.get().yesTokenId(), tokens.get().noTokenId(), volume, PolymarketMarketParser.endEpochMillis(m)));
      }
    }
    return out;
  }

  private boolean gammaEventHasTag(JsonNode event, String tagNeedle) {
    if (event == null || event.isNull() || tagNeedle == null || tagNeedle.isBlank()) {
      return false;
    }
    JsonNode tags = event.get("tags");
    if (tags == null || !tags.isArray() || tags.isEmpty()) {
      return false;
    }
    String needle = tagNeedle.trim().toLowerCase(Locale.ROOT);
    for (JsonNode t : tags) {
      if (t == null || t.isNull()) {
        continue;
      }
      String label = t.hasNonNull("label") ? t.get("label").asText("") : "";
      String slug = t.hasNonNull("slug") ? t.get("slug").asText("") : "";
      if (label != null && !label.isBlank() && label.trim().toLowerCase(Locale.ROOT).equals(needle)) {
        return true;
      }
      if (slug != null && !slug.isBlank() && slug.trim().toLowerCase(Locale.ROOT).equals(needle)) {
        return true;
      }
    }
    return false;
  }
}
