package com.polybot.hft.polymarket.strategy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.domain.OrderSide;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.hft.events.HftEventTypes;
import com.polybot.hft.polymarket.api.LimitOrderRequest;
import com.polybot.hft.polymarket.api.OrderSubmissionResult;
import com.polybot.hft.polymarket.model.ClobOrderType;
import com.polybot.hft.polymarket.strategy.config.GabagoolConfig;
import com.polybot.hft.polymarket.strategy.event.OrderLifecycleEvent;
import com.polybot.hft.polymarket.strategy.model.Direction;
import com.polybot.hft.polymarket.strategy.model.GabagoolMarket;
import com.polybot.hft.polymarket.strategy.model.MarketInventory;
import com.polybot.hft.polymarket.strategy.model.OrderState;
import com.polybot.hft.polymarket.ws.TopOfBook;
import com.polybot.hft.strategy.executor.ExecutorApiClient;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Manages order placement, cancellation, and status polling.
 */
@Slf4j
public class OrderManager {

    private static final Duration ORDER_STALE_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration ORDER_STATUS_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final int ERROR_MAX_LEN = 512;

    private final ExecutorApiClient executorApi;
    private final HftEventPublisher events;
    private final Clock clock;
    private final String runId;

    private final Map<String, OrderState> ordersByTokenId = new ConcurrentHashMap<>();

    public OrderManager(ExecutorApiClient executorApi, HftEventPublisher events, Clock clock, String runId) {
        this.executorApi = executorApi;
        this.events = events;
        this.clock = clock;
        this.runId = runId;
    }

    public Map<String, OrderState> getOpenOrders() {
        return Map.copyOf(ordersByTokenId);
    }

    public OrderState getOrder(String tokenId) {
        return ordersByTokenId.get(tokenId);
    }

    public boolean hasOrder(String tokenId) {
        return ordersByTokenId.containsKey(tokenId);
    }

    /**
     * Place a maker order for a token.
     */
    public void placeOrder(GabagoolMarket market, String tokenId, Direction direction,
                           BigDecimal price, BigDecimal size, long secondsToEnd,
                           BigDecimal tickSize, TopOfBook book, TopOfBook otherBook,
                           OrderState replaced, PlaceReason placeReason) {
        String reason = replaced == null ? PlaceReason.QUOTE.name() : PlaceReason.REPLACE.name();
        if (placeReason != null) reason = placeReason.name();

        String replacedOrderId = replaced == null ? null : replaced.orderId();
        BigDecimal replacedPrice = replaced == null ? null : replaced.price();
        BigDecimal replacedSize = replaced == null ? null : replaced.size();
        Long replacedAgeMillis = replaced == null ? null : Duration.between(replaced.placedAt(), clock.instant()).toMillis();

        String otherTokenId = direction == Direction.UP ? market.downTokenId() : market.upTokenId();

        try {
            log.info("GABAGOOL: Placing {} order on {} at {} (size={}, secondsToEnd={})",
                    direction, market.slug(), price, size, secondsToEnd);

            LimitOrderRequest request = new LimitOrderRequest(
                    tokenId, OrderSide.BUY, price, size, ClobOrderType.GTC,
                    null, null, null, null, null, null, null
            );

            OrderSubmissionResult result = executorApi.placeLimitOrder(request);
            String orderId = resolveOrderId(result);

            if (orderId == null) {
                log.warn("GABAGOOL: Order submission returned null orderId for {}", market.slug());
                publishOrderEvent(new OrderLifecycleEvent(
                        "gabagool-directional", runId, "PLACE", reason,
                        market.slug(), market.marketType(), tokenId, direction.name(),
                        secondsToEnd, tickSize, false, "orderId null",
                        null, price, size, replacedOrderId, replacedPrice, replacedSize,
                        replacedAgeMillis, null, book, otherTokenId, otherBook
                ));
                return;
            }

            ordersByTokenId.put(tokenId, new OrderState(
                    orderId, market, tokenId, direction, price, size,
                    clock.instant(), BigDecimal.ZERO, null, secondsToEnd
            ));

            log.info("GABAGOOL: Order placed successfully: {} (direction={}, price={}, size={})",
                    orderId, direction, price, size);

            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", reason,
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, tickSize, true, null,
                    orderId, price, size, replacedOrderId, replacedPrice, replacedSize,
                    replacedAgeMillis, null, book, otherTokenId, otherBook
            ));
        } catch (Exception e) {
            log.error("GABAGOOL: Failed to place order on {}: {}", market.slug(), e.getMessage());
            publishOrderEvent(new OrderLifecycleEvent(
                    "gabagool-directional", runId, "PLACE", reason,
                    market.slug(), market.marketType(), tokenId, direction.name(),
                    secondsToEnd, tickSize, false, truncateError(e),
                    null, price, size, replacedOrderId, replacedPrice, replacedSize,
                    replacedAgeMillis, null, book, otherTokenId, otherBook
            ));
        }
    }

    /**
     * Cancel and replace an existing order if price/size changed.
     */
    public ReplaceDecision maybeReplaceOrder(String tokenId, BigDecimal newPrice, BigDecimal newSize,
                                             GabagoolConfig cfg, CancelReason cancelReason,
                                             long secondsToEnd, TopOfBook book, TopOfBook otherBook) {
        OrderState existing = ordersByTokenId.get(tokenId);
        if (existing == null) {
            return ReplaceDecision.PLACE; // No existing order, can place new one
        }

        long ageMillis = Duration.between(existing.placedAt(), clock.instant()).toMillis();
        if (ageMillis < cfg.minReplaceMillis()) {
            return ReplaceDecision.SKIP; // Too soon to replace
        }

        boolean samePrice = existing.price() != null && existing.price().compareTo(newPrice) == 0;
        boolean sameSize = existing.size() != null && existing.size().compareTo(newSize) == 0;
        if (samePrice && sameSize) {
            return ReplaceDecision.SKIP; // No change needed
        }

        CancelReason reason = (!samePrice && !sameSize)
                ? CancelReason.REPLACE_PRICE_AND_SIZE
                : (!samePrice ? CancelReason.REPLACE_PRICE : CancelReason.REPLACE_SIZE);

        safeCancel(existing, reason, secondsToEnd, book, otherBook);
        ordersByTokenId.remove(tokenId);
        return ReplaceDecision.REPLACE;
    }

    /**
     * Cancel an order by token ID.
     */
    public void cancelOrder(String tokenId, CancelReason reason, Long secondsToEnd, TopOfBook book, TopOfBook otherBook) {
        if (tokenId == null || tokenId.isBlank()) return;
        OrderState state = ordersByTokenId.remove(tokenId);
        safeCancel(state, reason, secondsToEnd, book, otherBook);
    }

    /**
     * Cancel all orders for a market.
     */
    public void cancelMarketOrders(GabagoolMarket market, CancelReason reason, Long secondsToEnd) {
        if (market == null) return;
        cancelOrder(market.upTokenId(), reason, secondsToEnd, null, null);
        cancelOrder(market.downTokenId(), reason, secondsToEnd, null, null);
    }

    /**
     * Cancel all orders (shutdown).
     */
    public void cancelAll(CancelReason reason) {
        ordersByTokenId.values().forEach(state -> safeCancel(state, reason, null, null, null));
        ordersByTokenId.clear();
    }

    /**
     * Check pending orders for fills/timeout and invoke callback on fill.
     */
    public void checkPendingOrders(BiConsumer<OrderState, BigDecimal> onFill) {
        Instant now = clock.instant();

        for (Map.Entry<String, OrderState> entry : ordersByTokenId.entrySet()) {
            String tokenId = entry.getKey();
            OrderState state = entry.getValue();
            if (state == null) continue;

            refreshOrderStatusIfDue(tokenId, state, now, onFill);
            state = ordersByTokenId.get(tokenId);
            if (state == null) continue;

            Duration pendingTime = Duration.between(state.placedAt(), now);
            if (pendingTime.compareTo(ORDER_STALE_TIMEOUT) > 0) {
                log.info("GABAGOOL: Cancelling stale order {} tokenId={} after {}s",
                        state.orderId(), tokenId, pendingTime.getSeconds());
                Long secondsToEndNow = state.market() == null ? null
                        : Duration.between(now, state.market().endTime()).getSeconds();
                safeCancel(state, CancelReason.STALE_TIMEOUT, secondsToEndNow, null, null);
                ordersByTokenId.remove(tokenId);
            }
        }
    }

    private void refreshOrderStatusIfDue(String tokenId, OrderState state, Instant now,
                                         BiConsumer<OrderState, BigDecimal> onFill) {
        if (state == null || state.orderId() == null || state.orderId().isBlank()) return;

        if (state.lastStatusCheckAt() != null &&
                Duration.between(state.lastStatusCheckAt(), now).compareTo(ORDER_STATUS_POLL_INTERVAL) < 0) {
            return;
        }

        JsonNode order;
        try {
            order = executorApi.getOrder(state.orderId());
        } catch (Exception e) {
            ordersByTokenId.put(tokenId, new OrderState(
                    state.orderId(), state.market(), state.tokenId(), state.direction(),
                    state.price(), state.size(), state.placedAt(), state.matchedSize(),
                    now, state.secondsToEndAtEntry()
            ));
            return;
        }

        String status = firstText(order, "status", "state", "order_status", "orderStatus");
        BigDecimal matched = firstDecimal(order, "matched_size", "matchedSize", "size_matched",
                "sizeMatched", "filled_size", "filledSize", "size_filled", "sizeFilled");
        BigDecimal remaining = firstDecimal(order, "remaining_size", "remainingSize",
                "size_remaining", "sizeRemaining");

        if (remaining == null && matched != null && state.size() != null) {
            remaining = state.size().subtract(matched).max(BigDecimal.ZERO);
        }

        BigDecimal prevMatched = state.matchedSize() == null ? BigDecimal.ZERO : state.matchedSize();
        if (matched != null && matched.compareTo(prevMatched) > 0 && state.price() != null) {
            BigDecimal delta = matched.subtract(prevMatched);
            if (onFill != null) {
                onFill.accept(state, delta);
            }
        }

        if (isTerminalOrderStatus(status, matched, remaining, state.size())) {
            ordersByTokenId.remove(tokenId);
            return;
        }

        ordersByTokenId.put(tokenId, new OrderState(
                state.orderId(), state.market(), state.tokenId(), state.direction(),
                state.price(), state.size(), state.placedAt(),
                matched != null ? matched : prevMatched, now, state.secondsToEndAtEntry()
        ));
    }

    private void safeCancel(OrderState state, CancelReason reason, Long secondsToEndNow,
                           TopOfBook book, TopOfBook otherBook) {
        if (state == null || state.orderId() == null || state.orderId().isBlank()) return;

        boolean success = false;
        String error = null;
        try {
            executorApi.cancelOrder(state.orderId());
            success = true;
        } catch (Exception e) {
            error = truncateError(e);
        }

        String otherTokenId = null;
        if (state.market() != null && state.direction() != null) {
            otherTokenId = state.direction() == Direction.UP
                    ? state.market().downTokenId() : state.market().upTokenId();
        }

        publishOrderEvent(new OrderLifecycleEvent(
                "gabagool-directional", runId, "CANCEL", reason == null ? null : reason.name(),
                state.market() == null ? null : state.market().slug(),
                state.market() == null ? null : state.market().marketType(),
                state.tokenId(), state.direction() == null ? null : state.direction().name(),
                secondsToEndNow, null, success, error,
                state.orderId(), state.price(), state.size(), null, null, null, null,
                Duration.between(state.placedAt(), clock.instant()).toMillis(),
                book, otherTokenId, otherBook
        ));
    }

    private void publishOrderEvent(OrderLifecycleEvent event) {
        try {
            if (!events.isEnabled()) return;
            String key = event != null && event.orderId() != null && !event.orderId().isBlank()
                    ? event.orderId()
                    : ("gabagool:" + (event == null ? "unknown" : event.marketSlug()) + ":"
                            + (event == null ? "unknown" : event.tokenId()));
            events.publish(HftEventTypes.STRATEGY_GABAGOOL_ORDER, key, event);
        } catch (Exception e) {
            log.warn("Failed to publish order event: {}", e.getMessage());
        }
    }

    private static boolean isTerminalOrderStatus(String status, BigDecimal matched,
                                                 BigDecimal remaining, BigDecimal requestedSize) {
        if (remaining != null && remaining.compareTo(BigDecimal.ZERO) == 0) return true;
        if (matched != null && requestedSize != null && matched.compareTo(requestedSize) >= 0) return true;
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase(Locale.ROOT);
        return s.contains("FILLED") || s.contains("CANCELED") || s.contains("CANCELLED")
                || s.contains("EXPIRED") || s.contains("REJECTED") || s.contains("FAILED")
                || s.contains("DONE") || s.contains("CLOSED");
    }

    private static String resolveOrderId(OrderSubmissionResult result) {
        if (result == null) return null;
        JsonNode resp = result.clobResponse();
        if (resp != null) {
            if (resp.hasNonNull("orderID")) return resp.get("orderID").asText();
            if (resp.hasNonNull("orderId")) return resp.get("orderId").asText();
        }
        return null;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) continue;
            String s = v.asText(null);
            if (s != null && !s.isBlank()) return s;
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... keys) {
        if (node == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) continue;
            try {
                if (v.isNumber()) return v.decimalValue();
                String s = v.asText(null);
                if (s == null || s.isBlank()) continue;
                return new BigDecimal(s.trim());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String truncateError(Throwable t) {
        if (t == null) return null;
        String s = t.toString();
        return s.length() <= ERROR_MAX_LEN ? s : s.substring(0, ERROR_MAX_LEN) + "...";
    }

    public enum PlaceReason {
        QUOTE, REPLACE, TOP_UP, FAST_TOP_UP, TAKER
    }

    public enum ReplaceDecision {
        SKIP, PLACE, REPLACE
    }

    public enum CancelReason {
        BOOK_STALE, OUTSIDE_TIME_WINDOW, OUTSIDE_LIFETIME,
        REPLACE_PRICE, REPLACE_SIZE, REPLACE_PRICE_AND_SIZE,
        STALE_TIMEOUT, SHUTDOWN, INSUFFICIENT_EDGE
    }
}
