package com.polymarket.hft.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hft")
public class HftProperties {

    private TradingMode mode = TradingMode.PAPER;

    private final Polymarket polymarket = new Polymarket();

    private final Risk risk = new Risk();

    private final Strategy strategy = new Strategy();

    public TradingMode getMode() {
        return mode;
    }

    public void setMode(TradingMode mode) {
        this.mode = mode;
    }

    public Polymarket getPolymarket() {
        return polymarket;
    }

    public Risk getRisk() {
        return risk;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public enum TradingMode {
        PAPER,
        LIVE,
    }

    public static final class Polymarket {
        private String clobRestUrl = "https://clob.polymarket.com";
        private String clobWsUrl = "wss://ws-subscriptions-clob.polymarket.com";
        private int chainId = 137;
        private boolean useServerTime = true;

        private boolean marketWsEnabled = false;
        private final List<String> marketAssetIds = new ArrayList<>();

        private boolean userWsEnabled = false;
        private final List<String> userMarketIds = new ArrayList<>();

        private final Auth auth = new Auth();

        public String getClobRestUrl() {
            return clobRestUrl;
        }

        public void setClobRestUrl(String clobRestUrl) {
            this.clobRestUrl = clobRestUrl;
        }

        public String getClobWsUrl() {
            return clobWsUrl;
        }

        public void setClobWsUrl(String clobWsUrl) {
            this.clobWsUrl = clobWsUrl;
        }

        public int getChainId() {
            return chainId;
        }

        public void setChainId(int chainId) {
            this.chainId = chainId;
        }

        public boolean isUseServerTime() {
            return useServerTime;
        }

        public void setUseServerTime(boolean useServerTime) {
            this.useServerTime = useServerTime;
        }

        public boolean isMarketWsEnabled() {
            return marketWsEnabled;
        }

        public void setMarketWsEnabled(boolean marketWsEnabled) {
            this.marketWsEnabled = marketWsEnabled;
        }

        public List<String> getMarketAssetIds() {
            return marketAssetIds;
        }

        public boolean isUserWsEnabled() {
            return userWsEnabled;
        }

        public void setUserWsEnabled(boolean userWsEnabled) {
            this.userWsEnabled = userWsEnabled;
        }

        public List<String> getUserMarketIds() {
            return userMarketIds;
        }

        public Auth getAuth() {
            return auth;
        }
    }

    public static final class Auth {
        private String privateKey;
        private int signatureType = 0;
        private String funderAddress;

        private String apiKey;
        private String apiSecret;
        private String apiPassphrase;

        private long nonce = 0;
        private boolean autoCreateOrDeriveApiCreds = false;

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public int getSignatureType() {
            return signatureType;
        }

        public void setSignatureType(int signatureType) {
            this.signatureType = signatureType;
        }

        public String getFunderAddress() {
            return funderAddress;
        }

        public void setFunderAddress(String funderAddress) {
            this.funderAddress = funderAddress;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public String getApiPassphrase() {
            return apiPassphrase;
        }

        public void setApiPassphrase(String apiPassphrase) {
            this.apiPassphrase = apiPassphrase;
        }

        public long getNonce() {
            return nonce;
        }

        public void setNonce(long nonce) {
            this.nonce = nonce;
        }

        public boolean isAutoCreateOrDeriveApiCreds() {
            return autoCreateOrDeriveApiCreds;
        }

        public void setAutoCreateOrDeriveApiCreds(boolean autoCreateOrDeriveApiCreds) {
            this.autoCreateOrDeriveApiCreds = autoCreateOrDeriveApiCreds;
        }
    }

    public static final class Risk {
        private boolean killSwitch = false;
        private BigDecimal maxOrderNotionalUsd = BigDecimal.ZERO;
        private BigDecimal maxOrderSize = BigDecimal.ZERO;

        public boolean isKillSwitch() {
            return killSwitch;
        }

        public void setKillSwitch(boolean killSwitch) {
            this.killSwitch = killSwitch;
        }

        public BigDecimal getMaxOrderNotionalUsd() {
            return maxOrderNotionalUsd;
        }

        public void setMaxOrderNotionalUsd(BigDecimal maxOrderNotionalUsd) {
            this.maxOrderNotionalUsd = maxOrderNotionalUsd;
        }

        public BigDecimal getMaxOrderSize() {
            return maxOrderSize;
        }

        public void setMaxOrderSize(BigDecimal maxOrderSize) {
            this.maxOrderSize = maxOrderSize;
        }
    }

    public static final class Strategy {
        private final MidpointMaker midpointMaker = new MidpointMaker();

        public MidpointMaker getMidpointMaker() {
            return midpointMaker;
        }
    }

    public static final class MidpointMaker {
        private boolean enabled = false;
        private BigDecimal quoteSize = BigDecimal.valueOf(5);
        private BigDecimal spread = BigDecimal.valueOf(0.01);
        private long refreshMillis = 1_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BigDecimal getQuoteSize() {
            return quoteSize;
        }

        public void setQuoteSize(BigDecimal quoteSize) {
            this.quoteSize = quoteSize;
        }

        public BigDecimal getSpread() {
            return spread;
        }

        public void setSpread(BigDecimal spread) {
            this.spread = spread;
        }

        public long getRefreshMillis() {
            return refreshMillis;
        }

        public void setRefreshMillis(long refreshMillis) {
            this.refreshMillis = refreshMillis;
        }
    }
}
