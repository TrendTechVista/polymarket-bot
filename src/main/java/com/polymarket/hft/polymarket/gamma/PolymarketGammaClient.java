package com.polymarket.hft.polymarket.gamma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.polymarket.http.HttpHeadersUtil;
import com.polymarket.hft.polymarket.http.HttpRequestFactory;
import com.polymarket.hft.polymarket.http.PolymarketHttpTransport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class PolymarketGammaClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

  private final HttpRequestFactory requestFactory;
  private final PolymarketHttpTransport transport;
  private final ObjectMapper objectMapper;

  public PolymarketGammaClient(URI baseUri, PolymarketHttpTransport transport, ObjectMapper objectMapper) {
    this.requestFactory = new HttpRequestFactory(Objects.requireNonNull(baseUri, "baseUri"));
    this.transport = Objects.requireNonNull(transport, "transport");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public JsonNode search(Map<String, String> query, Map<String, String> headers) {
    return getJsonNode(PolymarketGammaPaths.SEARCH, query, headers);
  }

  public JsonNode markets(Map<String, String> query, Map<String, String> headers) {
    return getJsonNode(PolymarketGammaPaths.MARKETS, query, headers);
  }

  public JsonNode marketById(String id, Map<String, String> headers) {
    return getJsonNode(PolymarketGammaPaths.MARKETS + "/" + id, Map.of(), headers);
  }

  public JsonNode events(Map<String, String> query, Map<String, String> headers) {
    return getJsonNode(PolymarketGammaPaths.EVENTS, query, headers);
  }

  public JsonNode eventById(String id, Map<String, String> headers) {
    return getJsonNode(PolymarketGammaPaths.EVENTS + "/" + id, Map.of(), headers);
  }

  private JsonNode getJsonNode(String path, Map<String, String> query, Map<String, String> headers) {
    HttpRequest.Builder builder = requestFactory.request(path, query)
        .GET()
        .timeout(HTTP_TIMEOUT)
        .header("Accept", "application/json")
        .header("User-Agent", "polymarket-hft-service/1.0");
    HttpHeadersUtil.apply(builder, headers);
    return transport.sendJson(builder.build(), JsonNode.class);
  }
}
