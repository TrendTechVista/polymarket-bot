package com.polybot.hft.polymarket.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@UtilityClass
public class PolymarketMarketParser {

  private static final List<String> VOLUME_FIELDS = List.of(
      "volume",
      "volume24hr",
      "volume24h",
      "volumeUsd",
      "volume_usd",
      "volume24hrUsd",
      "volume24hUsd",
      "volume24hr_usd",
      "volume24h_usd"
  );

  public static String question(JsonNode market) {
    String q = text(market, "question");
    if (q == null || q.isBlank()) {
      q = text(market, "title");
    }
    if (q == null || q.isBlank()) {
      q = text(market, "name");
    }
    return q == null ? null : q.trim();
  }

  public static String id(JsonNode market) {
    String id = text(market, "id");
    if (id == null || id.isBlank()) {
      id = text(market, "marketId");
    }
    if (id == null || id.isBlank()) {
      id = text(market, "conditionId");
    }
    return id == null ? null : id.trim();
  }

  public static String slug(JsonNode market) {
    String slug = text(market, "slug");
    return slug == null ? null : slug.trim();
  }

  public static boolean isLive(JsonNode market) {
    if (market == null || market.isNull()) {
      return false;
    }

    if (market.hasNonNull("active") && !market.get("active").asBoolean(true)) {
      return false;
    }
    if (market.hasNonNull("closed") && market.get("closed").asBoolean(false)) {
      return false;
    }
    if (market.hasNonNull("archived") && market.get("archived").asBoolean(false)) {
      return false;
    }
    if (market.hasNonNull("isResolved") && market.get("isResolved").asBoolean(false)) {
      return false;
    }
    if (market.hasNonNull("resolved") && market.get("resolved").asBoolean(false)) {
      return false;
    }

    String status = text(market, "status");
    if (status != null) {
      String s = status.trim().toLowerCase(Locale.ROOT);
      if (s.contains("resolved") || s.contains("closed") || s.contains("final")) {
        return false;
      }
    }

    return true;
  }

  public static BigDecimal volume(JsonNode market) {
    if (market == null || market.isNull()) {
      return null;
    }
    for (String field : VOLUME_FIELDS) {
      JsonNode v = market.get(field);
      BigDecimal parsed = parseBigDecimal(v);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  public static Long endEpochMillis(JsonNode market) {
    if (market == null || market.isNull()) {
      return null;
    }

    for (String field : List.of(
        "endDate",
        "end_date",
        "endTime",
        "end_time",
        "closeDate",
        "close_date",
        "closingTime",
        "closing_time",
        "endTimestamp",
        "end_timestamp",
        "endTs",
        "end_ts"
    )) {
      JsonNode v = market.get(field);
      Long parsed = parseEpochMillis(v);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  public static Optional<YesNoTokens> yesNoTokens(JsonNode market, ObjectMapper objectMapper) {
    if (market == null || market.isNull()) {
      return Optional.empty();
    }
    Optional<YesNoTokens> fromGammaFields = yesNoTokensFromOutcomesAndClobTokenIds(market, objectMapper);
    if (fromGammaFields.isPresent()) {
      return fromGammaFields;
    }
    Optional<YesNoTokens> fromTokensArray = yesNoTokensFromTokensArray(market);
    if (fromTokensArray.isPresent()) {
      return fromTokensArray;
    }
    return yesNoTokensFromFlatFields(market);
  }

  public static List<JsonNode> extractMarkets(JsonNode root) {
    if (root == null || root.isNull()) {
      return List.of();
    }

    if (root.isArray()) {
      boolean looksLikeEvents = false;
      for (int i = 0; i < Math.min(3, root.size()); i++) {
        JsonNode n = root.get(i);
        if (n != null && n.has("markets") && n.get("markets").isArray()) {
          looksLikeEvents = true;
          break;
        }
      }
      if (looksLikeEvents) {
        return extractMarketsFromEvents(root);
      }
      return asList(root);
    }

    if (root.has("markets") && root.get("markets").isArray()) {
      return asList(root.get("markets"));
    }
    if (root.has("events") && root.get("events").isArray()) {
      return extractMarketsFromEvents(root.get("events"));
    }
    if (root.has("data") && root.get("data").isArray()) {
      JsonNode data = root.get("data");
      boolean looksLikeEvents = false;
      for (int i = 0; i < Math.min(3, data.size()); i++) {
        JsonNode n = data.get(i);
        if (n != null && n.has("markets") && n.get("markets").isArray()) {
          looksLikeEvents = true;
          break;
        }
      }
      if (looksLikeEvents) {
        return extractMarketsFromEvents(data);
      }
      return asList(data);
    }
    if (root.has("results") && root.get("results").isArray()) {
      return asList(root.get("results"));
    }

    return List.of();
  }

  private static List<JsonNode> extractMarketsFromEvents(JsonNode eventsArray) {
    if (eventsArray == null || !eventsArray.isArray()) {
      return List.of();
    }
    List<JsonNode> markets = new ArrayList<>();
    for (JsonNode event : eventsArray) {
      JsonNode m = event.get("markets");
      if (m != null && m.isArray()) {
        markets.addAll(asList(m));
      }
    }
    return markets;
  }

  private static List<JsonNode> asList(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    List<JsonNode> list = new ArrayList<>(arrayNode.size());
    for (JsonNode n : arrayNode) {
      list.add(n);
    }
    return list;
  }

  private static Optional<YesNoTokens> yesNoTokensFromOutcomesAndClobTokenIds(JsonNode market, ObjectMapper objectMapper) {
    JsonNode outcomesNode = market.get("outcomes");
    JsonNode clobTokenIdsNode = market.get("clobTokenIds");
    if (outcomesNode == null || clobTokenIdsNode == null) {
      outcomesNode = market.get("outcome");
      clobTokenIdsNode = market.get("clob_token_ids");
    }
    if (outcomesNode == null || clobTokenIdsNode == null) {
      return Optional.empty();
    }

    List<String> outcomes = parseStringArray(outcomesNode, objectMapper);
    List<String> tokenIds = parseStringArray(clobTokenIdsNode, objectMapper);
    if (outcomes.isEmpty() || tokenIds.isEmpty()) {
      return Optional.empty();
    }

    int n = Math.min(outcomes.size(), tokenIds.size());
    String yes = null;
    String no = null;
    for (int i = 0; i < n; i++) {
      String outcome = outcomes.get(i);
      String tokenId = tokenIds.get(i);
      if (outcome == null || tokenId == null) {
        continue;
      }
      String o = outcome.trim().toUpperCase(Locale.ROOT);
      if ("YES".equals(o)) {
        yes = tokenId.trim();
      } else if ("NO".equals(o)) {
        no = tokenId.trim();
      }
    }

    if (yes == null || yes.isBlank() || no == null || no.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new YesNoTokens(yes, no));
  }

  private static Optional<YesNoTokens> yesNoTokensFromTokensArray(JsonNode market) {
    JsonNode tokens = market.get("tokens");
    if (tokens == null || !tokens.isArray()) {
      return Optional.empty();
    }

    String yes = null;
    String no = null;
    for (JsonNode t : tokens) {
      String outcome = text(t, "outcome");
      if (outcome == null || outcome.isBlank()) {
        outcome = text(t, "name");
      }
      if (outcome == null) {
        continue;
      }
      String tokenId = text(t, "token_id");
      if (tokenId == null || tokenId.isBlank()) {
        tokenId = text(t, "tokenId");
      }
      if (tokenId == null || tokenId.isBlank()) {
        tokenId = text(t, "asset_id");
      }
      if (tokenId == null || tokenId.isBlank()) {
        continue;
      }

      String o = outcome.trim().toUpperCase(Locale.ROOT);
      if ("YES".equals(o)) {
        yes = tokenId.trim();
      } else if ("NO".equals(o)) {
        no = tokenId.trim();
      }
    }
    if (yes == null || yes.isBlank() || no == null || no.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new YesNoTokens(yes, no));
  }

  private static Optional<YesNoTokens> yesNoTokensFromFlatFields(JsonNode market) {
    String yes = firstText(market, List.of("yesTokenId", "yes_token_id", "yesTokenID", "yes_token"));
    String no = firstText(market, List.of("noTokenId", "no_token_id", "noTokenID", "no_token"));
    if (yes == null || yes.isBlank() || no == null || no.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new YesNoTokens(yes.trim(), no.trim()));
  }

  private static List<String> parseStringArray(JsonNode node, ObjectMapper objectMapper) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (node.isArray()) {
      List<String> out = new ArrayList<>(node.size());
      for (JsonNode n : node) {
        if (n != null && !n.isNull()) {
          String s = n.asText(null);
          if (s != null) {
            out.add(s);
          }
        }
      }
      return out;
    }
    if (node.isTextual()) {
      String raw = node.asText();
      if (raw == null || raw.isBlank()) {
        return List.of();
      }
      try {
        JsonNode parsed = objectMapper.readTree(raw);
        return parseStringArray(parsed, objectMapper);
      } catch (Exception ignored) {
        return List.of();
      }
    }
    return List.of();
  }

  private static String firstText(JsonNode node, List<String> keys) {
    for (String k : keys) {
      String v = text(node, k);
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static String text(JsonNode node, String field) {
    if (node == null || node.isNull() || field == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    if (v.isTextual()) {
      return v.asText();
    }
    if (v.isNumber()) {
      return v.asText();
    }
    return v.asText(null);
  }

  private static BigDecimal parseBigDecimal(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isTextual()) {
      String s = node.asText();
      if (s == null || s.isBlank()) {
        return null;
      }
      try {
        return new BigDecimal(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static Long parseEpochMillis(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isNumber()) {
      long value = node.asLong();
      if (value <= 0) {
        return null;
      }
      // Heuristic: values < 10^12 are seconds, otherwise millis.
      return value < 1_000_000_000_000L ? value * 1000L : value;
    }
    if (node.isTextual()) {
      String s = node.asText();
      if (s == null || s.isBlank()) {
        return null;
      }
      String trimmed = s.trim();
      try {
        return Instant.parse(trimmed).toEpochMilli();
      } catch (Exception ignored) {
      }
      if (trimmed.chars().allMatch(Character::isDigit)) {
        try {
          long value = Long.parseLong(trimmed);
          if (value <= 0) {
            return null;
          }
          return value < 1_000_000_000_000L ? value * 1000L : value;
        } catch (Exception ignored) {
        }
      }
    }
    return null;
  }
}
