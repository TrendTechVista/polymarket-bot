package com.polybot.ingestor.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketClobApiClient {

  private final @NonNull @Qualifier("polymarketClobRestClient") RestClient polymarketClobRestClient;
  private final @NonNull ObjectMapper objectMapper;

  public JsonNode getOrderBook(String tokenId) {
    String body = polymarketClobRestClient.get()
        .uri(uriBuilder -> uriBuilder
            .path("/book")
            .queryParam("token_id", tokenId)
            .build())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return objectMapper.createObjectNode();
    }

    try {
      return objectMapper.readTree(body);
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing clob book response tokenId=%s".formatted(tokenId), e);
    }
  }

  /**
   * Extract order book levels from a book response.
   * @param book The JSON response from /book endpoint
   * @param side "bids" or "asks"
   * @param limit Max number of levels to extract
   * @return List of [price, size] pairs
   */
  public List<Map<String, Double>> extractLevels(JsonNode book, String side, int limit) {
    List<Map<String, Double>> levels = new ArrayList<>();
    if (book == null || book.isMissingNode()) {
      return levels;
    }

    JsonNode sideNode = book.path(side);
    if (sideNode.isMissingNode() || !sideNode.isArray()) {
      return levels;
    }

    int count = Math.min(limit, sideNode.size());
    for (int i = 0; i < count; i++) {
      JsonNode level = sideNode.get(i);
      if (level == null || !level.isObject()) {
        continue;
      }

      double price = level.path("price").asDouble(0);
      double size = level.path("size").asDouble(0);

      if (price > 0 && size > 0) {
        levels.add(Map.of("price", price, "size", size));
      }
    }

    return levels;
  }

  /**
   * Calculate total volume for a side of the order book.
   */
  public double totalVolume(JsonNode book, String side) {
    if (book == null || book.isMissingNode()) {
      return 0;
    }

    JsonNode sideNode = book.path(side);
    if (sideNode.isMissingNode() || !sideNode.isArray()) {
      return 0;
    }

    double total = 0;
    for (JsonNode level : sideNode) {
      total += level.path("size").asDouble(0);
    }
    return total;
  }

  /**
   * Calculate book imbalance: (bids - asks) / (bids + asks)
   * Returns value between -1 (all asks) and +1 (all bids)
   */
  public double bookImbalance(JsonNode book) {
    double bids = totalVolume(book, "bids");
    double asks = totalVolume(book, "asks");
    double total = bids + asks;
    if (total <= 0) {
      return 0;
    }
    return (bids - asks) / total;
  }

  /**
   * Calculate total depth within X% of mid price.
   */
  public double depthWithinPercent(JsonNode book, double midPrice, double percentRange) {
    if (midPrice <= 0 || percentRange <= 0) {
      return 0;
    }

    double lowerBound = midPrice * (1 - percentRange);
    double upperBound = midPrice * (1 + percentRange);
    double depth = 0;

    JsonNode bids = book.path("bids");
    if (bids.isArray()) {
      for (JsonNode level : bids) {
        double price = level.path("price").asDouble(0);
        if (price >= lowerBound) {
          depth += level.path("size").asDouble(0);
        }
      }
    }

    JsonNode asks = book.path("asks");
    if (asks.isArray()) {
      for (JsonNode level : asks) {
        double price = level.path("price").asDouble(0);
        if (price <= upperBound) {
          depth += level.path("size").asDouble(0);
        }
      }
    }

    return depth;
  }

  public static boolean isNotFoundError(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return false;
    }
    String err = node.path("error").asText(null);
    return err != null && err.toLowerCase().contains("no orderbook exists");
  }
}

