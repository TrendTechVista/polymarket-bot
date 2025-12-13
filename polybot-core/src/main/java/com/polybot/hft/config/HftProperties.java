package com.polybot.hft.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Validated
@ConfigurationProperties(prefix="hft")
public record HftProperties(
    TradingMode mode,
    @Valid Polymarket polymarket,
    @Valid Executor executor,
    @Valid Risk risk,
    @Valid Strategy strategy
) {

  public HftProperties {
    if (mode == null) {
      mode = TradingMode.PAPER;
    }
    if (polymarket == null) {
      polymarket = defaultPolymarket();
    }
    if (executor == null) {
      executor = defaultExecutor();
    }
    if (risk == null) {
      risk = defaultRisk();
    }
    if (strategy == null) {
      strategy = defaultStrategy();
    }
  }

  private static List<String> sanitizeStringList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static List<HouseEdgeMarket> sanitizeHouseEdgeMarkets(List<HouseEdgeMarket> markets) {
    if (markets == null || markets.isEmpty()) {
      return List.of();
    }
    return markets.stream()
        .filter(Objects::nonNull)
        .map(m -> new HouseEdgeMarket(
            m.name() == null ? null : m.name().trim(),
            m.yesTokenId() == null ? null : m.yesTokenId().trim(),
            m.noTokenId() == null ? null : m.noTokenId().trim()
        ))
        .filter(m -> m.yesTokenId() != null && !m.yesTokenId().isBlank())
        .filter(m -> m.noTokenId() != null && !m.noTokenId().isBlank())
        .toList();
  }

  private static Executor defaultExecutor() {
    return new Executor(null, null);
  }

  private static Polymarket defaultPolymarket() {
    return new Polymarket(null, null, null, null, null, null, null, null, null, null, null);
  }

  private static Rest defaultRest() {
    return new Rest(null, null);
  }

  private static RateLimit defaultRateLimit() {
    return new RateLimit(null, null, null);
  }

  private static Retry defaultRetry() {
    return new Retry(null, null, null, null);
  }

  private static Auth defaultAuth() {
    return new Auth(null, null, null, null, null, null, null, null);
  }

  private static Risk defaultRisk() {
    return new Risk(false, null, null);
  }

  private static Strategy defaultStrategy() {
    return new Strategy(null);
  }

  private static HouseEdge defaultHouseEdge() {
    return new HouseEdge(false, null, null, null, null, null, null, null, null, null);
  }

  private static HouseEdgeDiscovery defaultHouseEdgeDiscovery() {
    return new HouseEdgeDiscovery(false, List.of("Bitcoin", "Ethereum"), null, null, null, null);
  }

  public enum TradingMode {
    PAPER,
    LIVE,
  }

  public record Executor(
      String baseUrl,
      @NotNull Boolean sendLiveAck
  ) {
    public Executor {
      if (baseUrl == null || baseUrl.isBlank()) {
        baseUrl = "http://localhost:8080";
      }
      if (sendLiveAck == null) {
        sendLiveAck = true;
      }
    }
  }

  public record Polymarket(
      String clobRestUrl,
      String clobWsUrl,
      String gammaUrl,
      @Min(1) Integer chainId,
      Boolean useServerTime,
      Boolean marketWsEnabled,
      Boolean userWsEnabled,
      List<String> marketAssetIds,
      List<String> userMarketIds,
      @Valid Rest rest,
      @Valid Auth auth
  ) {
    public Polymarket {
      if (clobRestUrl == null || clobRestUrl.isBlank()) {
        clobRestUrl = "https://clob.polymarket.com";
      }
      if (clobWsUrl == null || clobWsUrl.isBlank()) {
        clobWsUrl = "wss://ws-subscriptions-clob.polymarket.com";
      }
      if (gammaUrl == null || gammaUrl.isBlank()) {
        gammaUrl = "https://gamma-api.polymarket.com";
      }
      if (chainId == null) {
        chainId = 137;
      }
      if (useServerTime == null) {
        useServerTime = true;
      }
      if (marketWsEnabled == null) {
        marketWsEnabled = false;
      }
      if (userWsEnabled == null) {
        userWsEnabled = false;
      }
      marketAssetIds = sanitizeStringList(marketAssetIds);
      userMarketIds = sanitizeStringList(userMarketIds);
      if (rest == null) {
        rest = defaultRest();
      }
      if (auth == null) {
        auth = defaultAuth();
      }
    }
  }

  public record Rest(@Valid RateLimit rateLimit, @Valid Retry retry) {
    public Rest {
      if (rateLimit == null) {
        rateLimit = defaultRateLimit();
      }
      if (retry == null) {
        retry = defaultRetry();
      }
    }
  }

  public record RateLimit(
      @NotNull Boolean enabled,
      @NotNull @PositiveOrZero Double requestsPerSecond,
      @NotNull @PositiveOrZero Integer burst
  ) {
    public RateLimit {
      if (enabled == null) {
        enabled = true;
      }
      if (requestsPerSecond == null) {
        requestsPerSecond = 20.0;
      }
      if (burst == null) {
        burst = 50;
      }
    }
  }

  public record Retry(
      @NotNull Boolean enabled,
      @NotNull @Min(1) Integer maxAttempts,
      @NotNull @PositiveOrZero Long initialBackoffMillis,
      @NotNull @PositiveOrZero Long maxBackoffMillis
  ) {
    public Retry {
      if (enabled == null) {
        enabled = true;
      }
      if (maxAttempts == null) {
        maxAttempts = 3;
      }
      if (initialBackoffMillis == null) {
        initialBackoffMillis = 200L;
      }
      if (maxBackoffMillis == null) {
        maxBackoffMillis = 2_000L;
      }
    }
  }

  public record Auth(
      String privateKey,
      @NotNull @Min(0) Integer signatureType,
      String funderAddress,
      String apiKey,
      String apiSecret,
      String apiPassphrase,
      @NotNull @PositiveOrZero Long nonce,
      @NotNull Boolean autoCreateOrDeriveApiCreds
  ) {
    public Auth {
      if (signatureType == null) {
        signatureType = 0;
      }
      if (nonce == null) {
        nonce = 0L;
      }
      if (autoCreateOrDeriveApiCreds == null) {
        autoCreateOrDeriveApiCreds = false;
      }
    }
  }

  public record Risk(
      boolean killSwitch,
      @NotNull @PositiveOrZero BigDecimal maxOrderNotionalUsd,
      @NotNull @PositiveOrZero BigDecimal maxOrderSize
  ) {
    public Risk {
      if (maxOrderNotionalUsd == null) {
        maxOrderNotionalUsd = BigDecimal.ZERO;
      }
      if (maxOrderSize == null) {
        maxOrderSize = BigDecimal.ZERO;
      }
    }
  }

  public record Strategy(@Valid HouseEdge houseEdge) {
    public Strategy {
      if (houseEdge == null) {
        houseEdge = defaultHouseEdge();
      }
    }
  }

  public record HouseEdge(
      boolean enabled,
      @NotNull @Min(50) Long refreshMillis,
      @NotNull @Min(1) Integer tradeSamples,
      @NotNull @Min(1) Long tradeWindowSeconds,
      @NotNull @PositiveOrZero Integer aggressiveImproveTicks,
      @NotNull @PositiveOrZero Integer passiveAwayTicks,
      @NotNull @PositiveOrZero BigDecimal quoteSize,
      @NotNull @PositiveOrZero BigDecimal loserInventoryLimit,
      @Valid HouseEdgeDiscovery discovery,
      @Valid List<HouseEdgeMarket> markets
  ) {
    public HouseEdge {
      if (refreshMillis == null) {
        refreshMillis = 250L;
      }
      if (tradeSamples == null) {
        tradeSamples = 10;
      }
      if (tradeWindowSeconds == null) {
        tradeWindowSeconds = 30L;
      }
      if (aggressiveImproveTicks == null) {
        aggressiveImproveTicks = 1;
      }
      if (passiveAwayTicks == null) {
        passiveAwayTicks = 10;
      }
      if (quoteSize == null) {
        quoteSize = BigDecimal.valueOf(5);
      }
      if (loserInventoryLimit == null) {
        loserInventoryLimit = BigDecimal.ZERO;
      }
      if (discovery == null) {
        discovery = defaultHouseEdgeDiscovery();
      }
      markets = sanitizeHouseEdgeMarkets(markets);
    }
  }

  public record HouseEdgeDiscovery(
      boolean enabled,
      @NotNull List<String> queries,
      @NotNull Boolean require15m,
      @NotNull @Min(1) Integer maxMarkets,
      @NotNull @PositiveOrZero BigDecimal minVolume,
      @NotNull @Min(5) Long refreshSeconds
  ) {
    public HouseEdgeDiscovery {
      queries = sanitizeStringList(queries);
      if (require15m == null) {
        require15m = true;
      }
      if (maxMarkets == null) {
        maxMarkets = 3;
      }
      if (minVolume == null) {
        minVolume = BigDecimal.ZERO;
      }
      if (refreshSeconds == null) {
        refreshSeconds = 30L;
      }
    }
  }

  public record HouseEdgeMarket(
      String name,
      String yesTokenId,
      String noTokenId
  ) {
  }

}
