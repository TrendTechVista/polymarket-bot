package com.polybot.hft.strategy.web;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.strategy.GabagoolDirectionalEngine;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
@Slf4j
public class StrategyStatusController {

  private final @NonNull HftProperties properties;
  private final @NonNull Environment environment;
  private final @NonNull ClobMarketWebSocketClient marketWs;
  private final @NonNull GabagoolDirectionalEngine gabagoolEngine;

  @GetMapping("/status")
  public ResponseEntity<StrategyStatusResponse> status() {
    HftProperties.Gabagool gabagool = properties.strategy().gabagool();
    return ResponseEntity.ok(new StrategyStatusResponse(
        properties.mode().name(),
        environment.getActiveProfiles(),
        properties.executor().baseUrl(),
        properties.polymarket().marketWsEnabled(),
        environment.getProperty("hft.polymarket.market-ws-enabled"),
        marketWs.isStarted(),
        marketWs.subscribedAssetCount(),
        gabagool.enabled(),
        gabagoolEngine.activeMarketCount(),
        gabagoolEngine.isRunning()
    ));
  }

  public record StrategyStatusResponse(
      String mode,
      String[] activeProfiles,
      String executorBaseUrl,
      boolean marketWsEnabled,
      String resolvedMarketWsEnabledProperty,
      boolean marketWsStarted,
      int marketWsSubscribedAssets,
      boolean gabagoolEnabled,
      int gabagoolActiveMarkets,
      boolean gabagoolRunning
  ) {
  }
}
