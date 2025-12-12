package com.polymarket.hft.polymarket.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.domain.OrderSide;
import com.polymarket.hft.polymarket.model.ClobOrderType;
import com.polymarket.hft.polymarket.service.PolymarketTradingService;
import com.polymarket.hft.polymarket.web.LimitOrderRequest;
import com.polymarket.hft.polymarket.web.OrderSubmissionResult;
import com.polymarket.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polymarket.hft.polymarket.ws.TopOfBook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class MidpointMakerEngine {

    private static final Logger log = LoggerFactory.getLogger(MidpointMakerEngine.class);
    private static final MathContext MIDPOINT_CTX = new MathContext(18, RoundingMode.HALF_UP);

    private final HftProperties properties;
    private final ClobMarketWebSocketClient marketWs;
    private final PolymarketTradingService tradingService;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "midpoint-maker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, QuoteState> quotesByTokenId = new ConcurrentHashMap<>();

    public MidpointMakerEngine(
            HftProperties properties,
            ClobMarketWebSocketClient marketWs,
            PolymarketTradingService tradingService
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.marketWs = Objects.requireNonNull(marketWs, "marketWs");
        this.tradingService = Objects.requireNonNull(tradingService, "tradingService");
    }

    @PostConstruct
    void startIfEnabled() {
        HftProperties.MidpointMaker cfg = properties.getStrategy().getMidpointMaker();
        if (!cfg.isEnabled()) {
            return;
        }
        if (!properties.getPolymarket().isMarketWsEnabled()) {
            log.warn("midpoint-maker enabled, but market WS disabled (hft.polymarket.market-ws-enabled=false).");
            return;
        }
        List<String> tokenIds = properties.getPolymarket().getMarketAssetIds();
        if (tokenIds == null || tokenIds.isEmpty()) {
            log.warn("midpoint-maker enabled, but no token IDs configured (hft.polymarket.market-asset-ids).");
            return;
        }

        long periodMs = Math.max(50, cfg.getRefreshMillis());
        executor.scheduleAtFixedRate(() -> tick(tokenIds, cfg), 0, periodMs, TimeUnit.MILLISECONDS);
        log.info("midpoint-maker started (tokens={}, refreshMillis={})", tokenIds.size(), periodMs);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void tick(List<String> tokenIds, HftProperties.MidpointMaker cfg) {
        for (String tokenId : tokenIds) {
            try {
                tickOne(tokenId, cfg);
            } catch (Exception e) {
                log.debug("midpoint-maker tick error for {}: {}", tokenId, e.toString());
            }
        }
    }

    private void tickOne(String tokenId, HftProperties.MidpointMaker cfg) {
        TopOfBook tob = marketWs.getTopOfBook(tokenId).orElse(null);
        if (tob == null || tob.bestBid() == null || tob.bestAsk() == null) {
            return;
        }
        if (tob.bestBid().compareTo(BigDecimal.ZERO) <= 0 || tob.bestAsk().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (tob.bestBid().compareTo(tob.bestAsk()) >= 0) {
            return;
        }

        BigDecimal tickSize = tradingService.getTickSize(tokenId);
        boolean negRisk = tradingService.isNegRisk(tokenId);
        int priceDecimals = Math.max(0, tickSize.stripTrailingZeros().scale());

        BigDecimal midpoint = tob.bestBid().add(tob.bestAsk()).divide(BigDecimal.valueOf(2), MIDPOINT_CTX);
        BigDecimal halfSpread = cfg.getSpread().divide(BigDecimal.valueOf(2), MIDPOINT_CTX);

        BigDecimal bidPrice = midpoint.subtract(halfSpread).setScale(priceDecimals, RoundingMode.DOWN);
        BigDecimal askPrice = midpoint.add(halfSpread).setScale(priceDecimals, RoundingMode.UP);

        BigDecimal minPrice = tickSize;
        BigDecimal maxPrice = BigDecimal.ONE.subtract(tickSize);
        bidPrice = clamp(bidPrice, minPrice, maxPrice);
        askPrice = clamp(askPrice, minPrice, maxPrice);
        if (bidPrice.compareTo(askPrice) >= 0) {
            return;
        }

        QuoteState prev = quotesByTokenId.remove(tokenId);
        if (prev != null) {
            safeCancel(prev.buyOrderId);
            safeCancel(prev.sellOrderId);
        }

        OrderSubmissionResult buy = tradingService.placeLimitOrder(new LimitOrderRequest(
                tokenId,
                OrderSide.BUY,
                bidPrice,
                cfg.getQuoteSize(),
                ClobOrderType.GTC,
                tickSize,
                negRisk,
                null,
                null,
                null,
                null,
                false
        ));

        OrderSubmissionResult sell = tradingService.placeLimitOrder(new LimitOrderRequest(
                tokenId,
                OrderSide.SELL,
                askPrice,
                cfg.getQuoteSize(),
                ClobOrderType.GTC,
                tickSize,
                negRisk,
                null,
                null,
                null,
                null,
                false
        ));

        quotesByTokenId.put(tokenId, new QuoteState(
                resolveOrderId(buy),
                resolveOrderId(sell)
        ));
    }

    private void safeCancel(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        try {
            tradingService.cancelOrder(orderId);
        } catch (Exception ignored) {
        }
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
        if (v.compareTo(min) < 0) {
            return min;
        }
        if (v.compareTo(max) > 0) {
            return max;
        }
        return v;
    }

    private static String resolveOrderId(OrderSubmissionResult result) {
        if (result == null) {
            return null;
        }
        if (result.mode() == HftProperties.TradingMode.PAPER) {
            return "paper-" + UUID.randomUUID();
        }
        JsonNode resp = result.clobResponse();
        if (resp == null) {
            return null;
        }
        if (resp.hasNonNull("orderID")) {
            return resp.get("orderID").asText();
        }
        if (resp.hasNonNull("orderId")) {
            return resp.get("orderId").asText();
        }
        if (resp.hasNonNull("order_id")) {
            return resp.get("order_id").asText();
        }
        return null;
    }

    private record QuoteState(String buyOrderId, String sellOrderId) {
    }
}

