package com.polybot.hft.polymarket.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class TokenBucketRateLimiter implements RequestRateLimiter {

  private final double tokensPerSecond;
  private final double burst;
  private final Clock clock;

  private double availableTokens;
  private Instant lastRefill;

  public TokenBucketRateLimiter(double tokensPerSecond, int burst, Clock clock) {
    if (tokensPerSecond <= 0) {
      throw new IllegalArgumentException("tokensPerSecond must be > 0");
    }
    if (burst <= 0) {
      throw new IllegalArgumentException("burst must be > 0");
    }
    this.tokensPerSecond = tokensPerSecond;
    this.burst = burst;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.availableTokens = burst;
    this.lastRefill = Instant.now(clock);
  }

  @Override
  public void acquire() {
    while (true) {
      long sleepMillis = 0;
      synchronized (this) {
        refill();
        if (availableTokens >= 1.0) {
          availableTokens -= 1.0;
          return;
        }
        double missing = 1.0 - availableTokens;
        sleepMillis = Math.max(1, (long) Math.ceil(missing / tokensPerSecond * 1000.0));
      }
      sleepQuietly(sleepMillis);
    }
  }

  private void refill() {
    Instant now = Instant.now(clock);
    Duration elapsed = Duration.between(lastRefill, now);
    if (elapsed.isNegative() || elapsed.isZero()) {
      return;
    }
    double add = elapsed.toNanos() / 1_000_000_000.0 * tokensPerSecond;
    availableTokens = Math.min(burst, availableTokens + add);
    lastRefill = now;
  }

  private static void sleepQuietly(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

