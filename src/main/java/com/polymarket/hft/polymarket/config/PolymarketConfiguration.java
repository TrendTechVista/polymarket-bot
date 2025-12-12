package com.polymarket.hft.polymarket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;
import com.polymarket.hft.polymarket.http.PolymarketHttpTransport;
import com.polymarket.hft.polymarket.http.RequestRateLimiter;
import com.polymarket.hft.polymarket.http.RetryPolicy;
import com.polymarket.hft.polymarket.http.TokenBucketRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
public class PolymarketConfiguration {

  @Bean
  public PolymarketHttpTransport polymarketHttpTransport(
      HftProperties properties,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    HftProperties.Polymarket polymarket = properties.getPolymarket();
    RequestRateLimiter rateLimiter = buildRateLimiter(polymarket.getRest().getRateLimit(), clock);
    RetryPolicy retry = buildRetryPolicy(polymarket.getRest().getRetry());
    return new PolymarketHttpTransport(httpClient, objectMapper, rateLimiter, retry);
  }

  @Bean
  public PolymarketClobClient polymarketClobClient(
      HftProperties properties,
      PolymarketHttpTransport transport,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    HftProperties.Polymarket polymarket = properties.getPolymarket();
    return new PolymarketClobClient(
        URI.create(polymarket.getClobRestUrl()),
        transport,
        objectMapper,
        clock,
        polymarket.getChainId(),
        polymarket.isUseServerTime()
    );
  }

  private static RequestRateLimiter buildRateLimiter(HftProperties.RateLimit cfg, Clock clock) {
    if (cfg == null || !cfg.isEnabled()) {
      return RequestRateLimiter.noop();
    }
    if (cfg.getRequestsPerSecond() <= 0 || cfg.getBurst() <= 0) {
      return RequestRateLimiter.noop();
    }
    return new TokenBucketRateLimiter(cfg.getRequestsPerSecond(), cfg.getBurst(), clock);
  }

  private static RetryPolicy buildRetryPolicy(HftProperties.Retry cfg) {
    if (cfg == null) {
      return new RetryPolicy(false, 1, 0, 0);
    }
    return new RetryPolicy(
        cfg.isEnabled(),
        Math.max(1, cfg.getMaxAttempts()),
        Math.max(0, cfg.getInitialBackoffMillis()),
        Math.max(0, cfg.getMaxBackoffMillis())
    );
  }
}
