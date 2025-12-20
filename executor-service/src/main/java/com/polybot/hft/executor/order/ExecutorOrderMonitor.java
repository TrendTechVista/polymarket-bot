package com.polybot.hft.executor.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.executor.events.ExecutorOrderStatusEvent;
import com.polybot.hft.executor.metrics.ExecutorMetricsService;
import com.polybot.hft.polymarket.service.PolymarketTradingService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class ExecutorOrderMonitor {

  private static final Duration TRACK_TTL = Duration.ofHours(6);
  private static final int MAX_ERRORS_PER_ORDER = 25;
  private static final int ORDER_JSON_MAX_LEN = 25_000;

  private final @NonNull HftProperties properties;
  private final @NonNull PolymarketTradingService tradingService;
  private final @NonNull HftEventPublisher events;
  private final @NonNull ObjectMapper objectMapper;
  private final @NonNull Clock clock;
  private final @NonNull ExecutorMetricsService metricsService;

  private final Map<String, TrackedOrder> trackedByOrderId = new ConcurrentHashMap<>();

  public void trackNewOrder(String orderId, String tokenId, OrderSide side, BigDecimal price, BigDecimal size) {
    if (orderId == null || orderId.isBlank()) {
      return;
    }
    trackedByOrderId.putIfAbsent(orderId, new TrackedOrder(orderId, tokenId, side, price, size, clock.instant(), null, null, null, 0));
  }

  @Scheduled(initialDelay = 2_000L, fixedDelay = 1_000L)
  void poll() {
    if (properties.mode() != HftProperties.TradingMode.LIVE) {
      return;
    }
    if (trackedByOrderId.isEmpty()) {
      return;
    }

    Instant now = clock.instant();
    for (TrackedOrder tracked : trackedByOrderId.values()) {
      if (tracked == null) {
        continue;
      }
      if (tracked.trackedAt() != null && Duration.between(tracked.trackedAt(), now).compareTo(TRACK_TTL) > 0) {
        trackedByOrderId.remove(tracked.orderId());
        continue;
      }
      pollOne(tracked);
    }
  }

  private void pollOne(TrackedOrder tracked) {
    if (tracked == null || tracked.orderId() == null || tracked.orderId().isBlank()) {
      return;
    }

    JsonNode order = null;
    String error = null;
    try {
      order = tradingService.getOrder(tracked.orderId());
    } catch (Exception e) {
      error = e.getMessage() != null ? e.getMessage() : e.toString();
    }

    String status = order == null ? null : firstText(order,
        "status",
        "state",
        "order_status",
        "orderStatus"
    );
    BigDecimal matched = order == null ? null : firstDecimal(order,
        "matched_size",
        "matchedSize",
        "size_matched",
        "sizeMatched",
        "filled_size",
        "filledSize",
        "size_filled",
        "sizeFilled"
    );
    BigDecimal remaining = order == null ? null : firstDecimal(order,
        "remaining_size",
        "remainingSize",
        "size_remaining",
        "sizeRemaining"
    );
    if (remaining == null && matched != null && tracked.requestedSize() != null) {
      remaining = tracked.requestedSize().subtract(matched);
      if (remaining.compareTo(BigDecimal.ZERO) < 0) {
        remaining = BigDecimal.ZERO;
      }
    }

    boolean changed = !Objects.equals(normalize(status), normalize(tracked.lastStatus()))
        || !decimalEq(matched, tracked.lastMatchedSize())
        || !decimalEq(remaining, tracked.lastRemainingSize())
        || error != null;

    if (changed && events.isEnabled()) {
      events.publish(clock.instant(), HftEventTypes.EXECUTOR_ORDER_STATUS, tracked.orderId(), new ExecutorOrderStatusEvent(
          tracked.orderId(),
          tracked.tokenId(),
          tracked.side(),
          tracked.requestedPrice(),
          tracked.requestedSize(),
          status,
          matched,
          remaining,
          truncateJson(order),
          error
      ));
    }

    if (error != null) {
      int nextErrors = tracked.errorCount() + 1;
      if (nextErrors >= MAX_ERRORS_PER_ORDER) {
        trackedByOrderId.remove(tracked.orderId());
      } else {
        trackedByOrderId.put(tracked.orderId(), tracked.withErrorCount(nextErrors));
      }
      return;
    }

    TrackedOrder updated = tracked.withLast(status, matched, remaining);
    trackedByOrderId.put(tracked.orderId(), updated);

    // Record metrics for terminal states
    if (isTerminal(status, matched, remaining, tracked.requestedSize())) {
      recordTerminalMetrics(status, matched, tracked.requestedSize());
      trackedByOrderId.remove(tracked.orderId());
    }
  }

  private static boolean isTerminal(String status, BigDecimal matched, BigDecimal remaining, BigDecimal requestedSize) {
    if (remaining != null && remaining.compareTo(BigDecimal.ZERO) == 0) {
      return true;
    }
    if (matched != null && requestedSize != null && matched.compareTo(requestedSize) >= 0) {
      return true;
    }
    if (status == null || status.isBlank()) {
      return false;
    }
    String s = status.trim().toUpperCase();
    return s.contains("FILLED")
        || s.contains("CANCELED")
        || s.contains("CANCELLED")
        || s.contains("EXPIRED")
        || s.contains("REJECTED")
        || s.contains("FAILED")
        || s.contains("DONE")
        || s.contains("CLOSED");
  }

  private static String normalize(String s) {
    return s == null ? null : s.trim().toUpperCase();
  }

  private static boolean decimalEq(BigDecimal a, BigDecimal b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.compareTo(b) == 0;
  }

  private static String firstText(JsonNode node, String... keys) {
    if (node == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonNode v = node.get(key);
      if (v == null || v.isNull()) {
        continue;
      }
      String s = v.asText(null);
      if (s != null && !s.isBlank()) {
        return s;
      }
    }
    return null;
  }

  private static BigDecimal firstDecimal(JsonNode node, String... keys) {
    if (node == null || keys == null) {
      return null;
    }
    for (String key : keys) {
      if (key == null || key.isBlank()) {
        continue;
      }
      JsonNode v = node.get(key);
      if (v == null || v.isNull()) {
        continue;
      }
      try {
        if (v.isNumber()) {
          return v.decimalValue();
        }
        String s = v.asText(null);
        if (s == null || s.isBlank()) {
          continue;
        }
        return new BigDecimal(s.trim());
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private String truncateJson(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      String json = objectMapper.writeValueAsString(node);
      if (json.length() <= ORDER_JSON_MAX_LEN) {
        return json;
      }
      return json.substring(0, ORDER_JSON_MAX_LEN) + "...";
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Record metrics when an order reaches a terminal state.
   */
  private void recordTerminalMetrics(String status, BigDecimal matched, BigDecimal requestedSize) {
    if (status == null) {
      return;
    }
    String normalized = status.trim().toUpperCase();

    // Check if fully filled
    if (normalized.contains("FILLED") || normalized.contains("DONE")) {
      boolean fullyFilled = matched != null && requestedSize != null && matched.compareTo(requestedSize) >= 0;
      if (fullyFilled || normalized.contains("FILLED")) {
        metricsService.recordOrderFilled(null); // slippage tracking can be added later
      }
    }
    // Check if cancelled
    else if (normalized.contains("CANCEL")) {
      metricsService.recordOrderCancelled();
    }
    // Check if rejected
    else if (normalized.contains("REJECT") || normalized.contains("FAILED")) {
      metricsService.recordOrderRejected();
    }
  }

  private record TrackedOrder(
      String orderId,
      String tokenId,
      OrderSide side,
      BigDecimal requestedPrice,
      BigDecimal requestedSize,
      Instant trackedAt,
      String lastStatus,
      BigDecimal lastMatchedSize,
      BigDecimal lastRemainingSize,
      int errorCount
  ) {
    TrackedOrder withLast(String status, BigDecimal matched, BigDecimal remaining) {
      return new TrackedOrder(orderId, tokenId, side, requestedPrice, requestedSize, trackedAt, status, matched, remaining, errorCount);
    }

    TrackedOrder withErrorCount(int next) {
      return new TrackedOrder(orderId, tokenId, side, requestedPrice, requestedSize, trackedAt, lastStatus, lastMatchedSize, lastRemainingSize, next);
    }
  }
}

