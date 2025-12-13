package com.polybot.hft.executor.web;

import com.polybot.hft.config.HftProperties;
import com.polybot.hft.polymarket.auth.PolymarketAuthContext;
import com.polybot.hft.polymarket.api.PolymarketAuthStatusResponse;
import com.polybot.hft.polymarket.api.PolymarketDeriveCredsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/polymarket/auth")
@RequiredArgsConstructor
@Slf4j
public class PolymarketAuthController {

  private final PolymarketAuthContext authContext;
  private final HftProperties properties;
  private final Environment environment;

  @GetMapping("/status")
  public ResponseEntity<PolymarketAuthStatusResponse> status() {
    log.info("api /auth/status");
    HftProperties.Auth auth = properties.polymarket().auth();
    String envPk = environment.getProperty("POLYMARKET_PRIVATE_KEY");
    String resolvedPk = environment.getProperty("hft.polymarket.auth.private-key");
    String resolvedAuto = environment.getProperty("hft.polymarket.auth.auto-create-or-derive-api-creds");
    return ResponseEntity.ok(new PolymarketAuthStatusResponse(
        properties.mode().name(),
        environment.getActiveProfiles(),
        authContext.signerCredentials().isPresent(),
        authContext.signerCredentials().map(c -> c.getAddress()).orElse(null),
        authContext.apiCreds().isPresent(),
        authContext.autoDeriveEnabled(),
        authContext.configuredNonce(),
        properties.polymarket().clobRestUrl(),
        envPk != null && !envPk.isBlank(),
        envPk == null ? null : envPk.trim().length(),
        resolvedPk != null && !resolvedPk.isBlank(),
        resolvedPk == null ? null : resolvedPk.trim().length(),
        resolvedAuto
    ));
  }

  /**
   * Attempts to create/derive API creds and store them in-memory, without returning secrets.
   * In LIVE mode, requires header {@link LiveTradingGuardFilter#HEADER_LIVE_ACK}: true.
   */
  @PostMapping("/derive")
  public ResponseEntity<PolymarketDeriveCredsResponse> derive(
      @RequestHeader(name = LiveTradingGuardFilter.HEADER_LIVE_ACK, required = false) String liveAck,
      @RequestParam(name = "nonce", required = false) Long nonceOverride
  ) {
    log.info("api /auth/derive nonce={} liveAck={}", nonceOverride, liveAck != null && !liveAck.isBlank());
    if (properties.mode() == HftProperties.TradingMode.LIVE && !"true".equalsIgnoreCase(liveAck)) {
      Long nonce = nonceOverride == null ? properties.polymarket().auth().nonce() : nonceOverride;
      return ResponseEntity.status(428).body(new PolymarketDeriveCredsResponse(
          false,
          false,
          null,
          nonce,
          "Refusing LIVE credentials derive without " + LiveTradingGuardFilter.HEADER_LIVE_ACK + ": true"
      ));
    }

    long nonce = nonceOverride == null ? properties.polymarket().auth().nonce() : nonceOverride;
    PolymarketAuthContext.DeriveAttempt attempt = authContext.tryCreateOrDeriveApiCreds(nonce);
    return ResponseEntity.ok(new PolymarketDeriveCredsResponse(
        attempt.attempted(),
        attempt.success(),
        attempt.method(),
        attempt.nonce(),
        attempt.error()
    ));
  }
}
