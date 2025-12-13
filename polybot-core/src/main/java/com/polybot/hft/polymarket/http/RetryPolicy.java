package com.polybot.hft.polymarket.http;

import java.util.Optional;

public record RetryPolicy(
    boolean enabled,
    int maxAttempts,
    long initialBackoffMillis,
    long maxBackoffMillis
) {

  private static Long parseRetryAfterSeconds(String raw) {
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    if (t.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(t);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  public boolean isRetryableStatus(int statusCode) {
    if (statusCode == 429 || statusCode == 408) {
      return true;
    }
    return statusCode >= 500 && statusCode <= 599;
  }

  public long computeDelayMillis(int attempt, Optional<String> retryAfterHeader) {
    if (retryAfterHeader != null && retryAfterHeader.isPresent()) {
      Long parsed = parseRetryAfterSeconds(retryAfterHeader.get());
      if (parsed != null && parsed > 0) {
        return parsed * 1000L;
      }
    }

    long base = Math.max(0, initialBackoffMillis);
    long max = Math.max(base, maxBackoffMillis);
    long delay = base;
    for (int i = 1; i < attempt; i++) {
      delay = Math.min(max, delay * 2);
    }
    return delay;
  }
}

