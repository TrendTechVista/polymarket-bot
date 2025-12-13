package com.polybot.hft.strategy.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.HftHeaders;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.http.HttpRequestFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExecutorApiClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final HftProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public BigDecimal getTickSize(String tokenId) {
    String path = "/api/polymarket/tick-size/" + tokenId;
    HttpRequest request = baseRequest(path, Map.of())
        .GET()
        .timeout(HTTP_TIMEOUT)
        .header("Accept", "application/json")
        .build();
    return sendJson(request, BigDecimal.class);
  }

  public OrderSubmissionResult placeLimitOrder(LimitOrderRequest requestBody) {
    String path = "/api/polymarket/orders/limit";
    String body = writeJson(requestBody);
    HttpRequest request = baseRequest(path, Map.of())
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(HTTP_TIMEOUT)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .build();
    return sendJson(request, OrderSubmissionResult.class);
  }

  public void cancelOrder(String orderId) {
    String path = "/api/polymarket/orders/" + orderId;
    HttpRequest request = baseRequest(path, Map.of())
        .DELETE()
        .timeout(HTTP_TIMEOUT)
        .header("Accept", "application/json")
        .build();
    sendString(request);
  }

  private HttpRequest.Builder baseRequest(String path, Map<String, String> query) {
    HttpRequest.Builder builder = requestFactory().request(path, query);
    if (Boolean.TRUE.equals(properties.executor().sendLiveAck())) {
      builder.header(HftHeaders.LIVE_ACK, "true");
    }
    return builder;
  }

  private HttpRequestFactory requestFactory() {
    return new HttpRequestFactory(URI.create(properties.executor().baseUrl()));
  }

  private <T> T sendJson(HttpRequest request, Class<T> type) {
    String body = sendString(request);
    try {
      return objectMapper.readValue(body, type);
    } catch (Exception e) {
      throw new RuntimeException("Failed to decode JSON response from " + request.uri(), e);
    }
  }

  private String sendString(HttpRequest request) {
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return response.body();
      }
      throw new RuntimeException("HTTP " + response.statusCode() + " from " + request.method() + " " + request.uri() + ": " + truncate(response.body()));
    } catch (Exception e) {
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException("Executor request failed: " + request.uri(), e);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to encode JSON", e);
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
  }
}

