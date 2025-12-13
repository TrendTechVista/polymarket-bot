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
    List<DiscoveredMarket> markets = new ArrayList<>();
    try {
      Map<String, String> gammaQuery = new LinkedHashMap<>();
      gammaQuery.put("q", DEFAULT_UP_OR_DOWN_QUERY);
      gammaQuery.put("tag", TAG_15M);
      gammaQuery.put("_sort", "ending_soon");

      JsonNode root = gammaClient.publicSearch(gammaQuery, Map.of());
      markets.addAll(parseMarkets("gamma-up-or-down-15m", root));
    } catch (Exception e) {
      log.debug("gamma up-or-down 15m public-search failed: {}", e.toString());
    }

    if (!markets.isEmpty()) {
      return markets;
    }

    try {
      JsonNode root = gammaClient.events(Map.of("search", DEFAULT_UP_OR_DOWN_QUERY), Map.of());
      markets.addAll(parseMarkets("gamma-up-or-down-15m-events", root));
    } catch (Exception e) {
      log.debug("gamma up-or-down 15m events search failed: {}", e.toString());
    }

    return markets;
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
}
