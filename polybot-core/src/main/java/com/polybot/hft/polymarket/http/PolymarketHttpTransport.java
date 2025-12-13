package com.polybot.hft.polymarket.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class PolymarketHttpTransport {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final RequestRateLimiter rateLimiter;
  private final RetryPolicy retryPolicy;

  public PolymarketHttpTransport(
      HttpClient httpClient,
      ObjectMapper objectMapper,
      RequestRateLimiter rateLimiter,
      RetryPolicy retryPolicy
  ) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
  }

  private static boolean isIdempotent(String method) {
    return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
  }

  private static long jitter(long delayMillis) {
    if (delayMillis <= 0) {
      return 0;
    }
    long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(250, delayMillis + 1));
    return delayMillis + jitter;
  }

  private static void sleepQuietly(long delayMillis) {
    if (delayMillis <= 0) {
      return;
    }
    try {
      TimeUnit.MILLISECONDS.sleep(delayMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public <T> T sendJson(HttpRequest request, Class<T> type) {
    String body = sendString(request, isIdempotent(request.method()));
    try {
      return objectMapper.readValue(body, type);
    } catch (IOException e) {
      throw new RuntimeException("Failed to decode JSON response from " + request.uri(), e);
    }
  }

  public String sendString(HttpRequest request, boolean idempotent) {
    int maxAttempts = (idempotent && retryPolicy.enabled())
        ? Math.max(1, retryPolicy.maxAttempts())
        : 1;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      rateLimiter.acquire();
      try {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return response.body();
        }

        if (attempt < maxAttempts && retryPolicy.isRetryableStatus(status)) {
          long delayMillis = retryPolicy.computeDelayMillis(
              attempt,
              response.headers().firstValue("retry-after")
          );
          sleepQuietly(jitter(delayMillis));
          continue;
        }

        throw new PolymarketHttpException(request.method(), request.uri(), status, response.body());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("HTTP request interrupted: " + request.uri(), e);
      } catch (IOException e) {
        if (attempt < maxAttempts) {
          long delayMillis = retryPolicy.computeDelayMillis(attempt, Optional.empty());
          sleepQuietly(jitter(delayMillis));
          continue;
        }
        throw new RuntimeException("HTTP request failed: " + request.uri(), e);
      }
    }

    throw new IllegalStateException("Unreachable");
  }
}

