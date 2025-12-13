package com.polybot.hft.strategy.web;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.strategy.HouseEdgeEngine;
import com.polybot.hft.polymarket.strategy.HouseEdgeMarketDiscoveryRunner;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
@Slf4j
public class StrategyStatusController {

  private final @NonNull HftProperties properties;
  private final @NonNull Environment environment;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull HouseEdgeEngine houseEdgeEngine;
  private final @NonNull HouseEdgeMarketDiscoveryRunner discoveryRunner;

  @GetMapping("/status")
  public ResponseEntity<StrategyStatusResponse> status() {
    HftProperties.HouseEdge houseEdge = properties.strategy().houseEdge();
    HftProperties.HouseEdgeDiscovery discovery = houseEdge.discovery();
    return ResponseEntity.ok(new StrategyStatusResponse(properties.mode().name(), environment.getActiveProfiles(), properties.executor().baseUrl(), properties.polymarket().marketWsEnabled(), environment.getProperty("hft.polymarket.market-ws-enabled"), marketWs.isStarted(), marketWs.subscribedAssetCount(), houseEdge.enabled(), discovery != null && discovery.enabled(), environment.getProperty("hft.strategy.house-edge.enabled"), environment.getProperty("hft.strategy.house-edge.discovery.enabled"), environment.getProperty("hft.strategy.house-edge.discovery.queries"), discovery == null ? List.of() : discovery.queries(), houseEdgeEngine.activeMarketCount(), discoveryRunner.lastRefreshEpochMillis(), discoveryRunner.lastSelectedMarkets()));
  }

  public record StrategyStatusResponse(String mode, String[] activeProfiles, String executorBaseUrl,
                                       boolean marketWsEnabled, String resolvedMarketWsEnabledProperty,
                                       boolean marketWsStarted, int marketWsSubscribedAssets, boolean houseEdgeEnabled,
                                       boolean houseEdgeDiscoveryEnabled, String resolvedHouseEdgeEnabledProperty,
                                       String resolvedHouseEdgeDiscoveryEnabledProperty,
                                       String resolvedHouseEdgeDiscoveryQueriesProperty, List<String> houseEdgeQueries,
                                       int houseEdgeActiveMarkets, long discoveryLastRefreshEpochMillis,
                                       int discoveryLastSelectedMarkets) {
  }
}
