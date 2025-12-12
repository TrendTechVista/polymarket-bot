package com.polymarket.hft.polymarket.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.domain.OrderSide;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;
import com.polymarket.hft.polymarket.model.ApiCreds;
import com.polymarket.hft.polymarket.model.ClobOrderType;
import com.polymarket.hft.polymarket.model.OrderBook;
import com.polymarket.hft.polymarket.model.SignedOrder;
import com.polymarket.hft.polymarket.order.PolymarketOrderBuilder;
import com.polymarket.hft.polymarket.web.LimitOrderRequest;
import com.polymarket.hft.polymarket.web.MarketOrderRequest;
import com.polymarket.hft.polymarket.web.OrderSubmissionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PolymarketTradingService {

    private static final Logger log = LoggerFactory.getLogger(PolymarketTradingService.class);

    private final HftProperties properties;
    private final PolymarketClobClient clobClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile Credentials signerCredentials;
    private volatile ApiCreds apiCreds;

    public PolymarketTradingService(
            HftProperties properties,
            PolymarketClobClient clobClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clobClient = Objects.requireNonNull(clobClient, "clobClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        initFromConfig();
    }

    public OrderBook getOrderBook(String tokenId) {
        return clobClient.getOrderBook(tokenId);
    }

    public BigDecimal getTickSize(String tokenId) {
        return clobClient.getMinimumTickSize(tokenId);
    }

    public boolean isNegRisk(String tokenId) {
        return clobClient.isNegRisk(tokenId);
    }

    public OrderSubmissionResult placeLimitOrder(LimitOrderRequest request) {
        if (properties.getRisk().isKillSwitch()) {
            throw new IllegalStateException("Trading disabled by kill switch (hft.risk.kill-switch=true)");
        }
        enforceRiskLimits(request.side(), request.price(), request.size());

        Credentials signer = requireSignerCredentials();
        SignedOrder order = orderBuilder(signer).buildLimitOrder(
                request.tokenId(),
                request.side(),
                request.price(),
                request.size(),
                resolveTickSize(request.tokenId(), request.tickSize()),
                resolveNegRisk(request.tokenId(), request.negRisk()),
                resolveFeeRateBps(request.tokenId(), request.feeRateBps()),
                request.nonce(),
                request.expirationSeconds(),
                request.taker()
        );

        if (properties.getMode() == HftProperties.TradingMode.PAPER) {
            return new OrderSubmissionResult(properties.getMode(), order, null);
        }

        ApiCreds creds = requireApiCreds();
        ClobOrderType orderType = request.orderType() == null ? ClobOrderType.GTC : request.orderType();
        JsonNode resp = clobClient.postOrder(
                signer,
                creds,
                order,
                orderType,
                request.deferExec() != null && request.deferExec()
        );
        return new OrderSubmissionResult(properties.getMode(), order, resp);
    }

    public OrderSubmissionResult placeMarketOrder(MarketOrderRequest request) {
        if (properties.getRisk().isKillSwitch()) {
            throw new IllegalStateException("Trading disabled by kill switch (hft.risk.kill-switch=true)");
        }
        enforceMarketRiskLimits(request.side(), request.price(), request.amount());

        Credentials signer = requireSignerCredentials();
        SignedOrder order = orderBuilder(signer).buildMarketOrder(
                request.tokenId(),
                request.side(),
                request.amount(),
                request.price(),
                resolveTickSize(request.tokenId(), request.tickSize()),
                resolveNegRisk(request.tokenId(), request.negRisk()),
                resolveFeeRateBps(request.tokenId(), request.feeRateBps()),
                request.nonce(),
                request.taker()
        );

        if (properties.getMode() == HftProperties.TradingMode.PAPER) {
            return new OrderSubmissionResult(properties.getMode(), order, null);
        }

        ApiCreds creds = requireApiCreds();
        ClobOrderType orderType = request.orderType() == null ? ClobOrderType.FOK : request.orderType();
        JsonNode resp = clobClient.postOrder(
                signer,
                creds,
                order,
                orderType,
                request.deferExec() != null && request.deferExec()
        );
        return new OrderSubmissionResult(properties.getMode(), order, resp);
    }

    public JsonNode cancelOrder(String orderId) {
        if (properties.getMode() == HftProperties.TradingMode.PAPER) {
            return objectMapper.createObjectNode()
                    .put("mode", properties.getMode().name())
                    .put("canceled", true)
                    .put("orderId", orderId);
        }

        Credentials signer = requireSignerCredentials();
        ApiCreds creds = requireApiCreds();
        return clobClient.cancelOrder(signer, creds, orderId);
    }

    private void initFromConfig() {
        String privateKey = properties.getPolymarket().getAuth().getPrivateKey();
        if (privateKey != null && !privateKey.isBlank()) {
            this.signerCredentials = Credentials.create(strip0x(privateKey));
        }

        String apiKey = properties.getPolymarket().getAuth().getApiKey();
        String apiSecret = properties.getPolymarket().getAuth().getApiSecret();
        String apiPassphrase = properties.getPolymarket().getAuth().getApiPassphrase();
        if (apiKey != null && !apiKey.isBlank()
                && apiSecret != null && !apiSecret.isBlank()
                && apiPassphrase != null && !apiPassphrase.isBlank()) {
            this.apiCreds = new ApiCreds(apiKey, apiSecret, apiPassphrase);
        }

        if (properties.getMode() == HftProperties.TradingMode.LIVE
                && properties.getPolymarket().getAuth().isAutoCreateOrDeriveApiCreds()
                && this.apiCreds == null) {
            Credentials signer = requireSignerCredentials();
            long nonce = properties.getPolymarket().getAuth().getNonce();
            ApiCreds derived = clobClient.createOrDeriveApiCreds(signer, nonce);
            this.apiCreds = derived;
            log.info("Loaded Polymarket API key creds (key={})", derived.key());
        }
    }

    private Credentials requireSignerCredentials() {
        if (signerCredentials == null) {
            throw new IllegalStateException("Polymarket signer private key is not configured (hft.polymarket.auth.private-key)");
        }
        return signerCredentials;
    }

    private ApiCreds requireApiCreds() {
        if (apiCreds == null) {
            throw new IllegalStateException("Polymarket API creds not configured (api-key/secret/passphrase)");
        }
        return apiCreds;
    }

    private PolymarketOrderBuilder orderBuilder(Credentials signer) {
        HftProperties.Polymarket polymarket = properties.getPolymarket();
        HftProperties.Auth auth = polymarket.getAuth();
        return new PolymarketOrderBuilder(
                polymarket.getChainId(),
                signer,
                auth.getSignatureType(),
                auth.getFunderAddress()
        );
    }

    private BigDecimal resolveTickSize(String tokenId, BigDecimal tickSizeOverride) {
        return tickSizeOverride != null ? tickSizeOverride : clobClient.getMinimumTickSize(tokenId);
    }

    private boolean resolveNegRisk(String tokenId, Boolean negRiskOverride) {
        return negRiskOverride != null ? negRiskOverride : clobClient.isNegRisk(tokenId);
    }

    private Integer resolveFeeRateBps(String tokenId, Integer feeRateBpsOverride) {
        return feeRateBpsOverride != null ? feeRateBpsOverride : clobClient.getBaseFeeBps(tokenId);
    }

    private void enforceRiskLimits(OrderSide side, BigDecimal price, BigDecimal size) {
        if (size == null || price == null) {
            return;
        }
        BigDecimal maxSize = properties.getRisk().getMaxOrderSize();
        if (maxSize != null && maxSize.compareTo(BigDecimal.ZERO) > 0 && size.compareTo(maxSize) > 0) {
            throw new IllegalArgumentException("Order size exceeds maxOrderSize (" + maxSize + ")");
        }

        BigDecimal notional = price.multiply(size);
        BigDecimal maxNotional = properties.getRisk().getMaxOrderNotionalUsd();
        if (maxNotional != null && maxNotional.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxNotional) > 0) {
            throw new IllegalArgumentException("Order notional exceeds maxOrderNotionalUsd (" + maxNotional + ")");
        }
    }

    private void enforceMarketRiskLimits(OrderSide side, BigDecimal price, BigDecimal amount) {
        if (price == null || amount == null || side == null) {
            return;
        }
        BigDecimal notional = side == OrderSide.BUY ? amount : amount.multiply(price);
        BigDecimal maxNotional = properties.getRisk().getMaxOrderNotionalUsd();
        if (maxNotional != null && maxNotional.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxNotional) > 0) {
            throw new IllegalArgumentException("Order notional exceeds maxOrderNotionalUsd (" + maxNotional + ")");
        }
    }

    private static String strip0x(String hex) {
        String trimmed = hex.trim();
        return trimmed.startsWith("0x") || trimmed.startsWith("0X") ? trimmed.substring(2) : trimmed;
    }
}
