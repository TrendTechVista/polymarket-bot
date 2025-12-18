package com.polybot.hft.polymarket.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.polymarket.http.HttpRequestFactory;
import com.polybot.hft.polymarket.http.PolymarketHttpTransport;
import lombok.NonNull;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PolymarketDataApiClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

  private final HttpRequestFactory requestFactory;
  private final PolymarketHttpTransport transport;

  public PolymarketDataApiClient(@NonNull URI baseUri, @NonNull PolymarketHttpTransport transport) {
    this.requestFactory = new HttpRequestFactory(Objects.requireNonNull(baseUri, "baseUri"));
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  public JsonNode getTrades(String userAddress, int limit, int offset) {
    return getArray("/trades", userAddress, limit, offset);
  }

  public JsonNode getPositions(String userAddress, int limit, int offset) {
    return getArray("/positions", userAddress, limit, offset);
  }

  private JsonNode getArray(String path, String userAddress, int limit, int offset) {
    if (userAddress == null || userAddress.isBlank()) {
      throw new IllegalArgumentException("userAddress must not be blank");
    }

    Map<String, String> query = new LinkedHashMap<>();
    query.put("user", userAddress);
    query.put("limit", Integer.toString(Math.max(1, limit)));
    query.put("offset", Integer.toString(Math.max(0, offset)));

    HttpRequest request = requestFactory.request(path, query)
        .GET()
        .timeout(HTTP_TIMEOUT)
        .header("Accept", "application/json")
        .build();
    return transport.sendJson(request, JsonNode.class);
  }
}

