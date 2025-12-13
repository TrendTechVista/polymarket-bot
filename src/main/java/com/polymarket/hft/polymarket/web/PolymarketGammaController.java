package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.polymarket.gamma.PolymarketGammaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/polymarket/gamma")
@RequiredArgsConstructor
@Slf4j
public class PolymarketGammaController {

  private final PolymarketGammaClient gammaClient;
  private final ObjectMapper objectMapper;

  @GetMapping("/search")
  public ResponseEntity<JsonNode> search(
      @RequestParam Map<String, String> query,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cookie", required = false) String cookie
  ) {
    String q = query.get("query");
    if (q == null || q.isBlank()) {
      return ResponseEntity.badRequest().body(objectMapper.createObjectNode()
          .put("error", "Missing required query parameter: query"));
    }
    log.info("api /gamma/search query='{}'", q);
    return ResponseEntity.ok(gammaClient.search(query, gammaHeaders(authorization, cookie)));
  }

  @GetMapping("/markets")
  public ResponseEntity<JsonNode> markets(
      @RequestParam Map<String, String> query,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cookie", required = false) String cookie
  ) {
    log.info("api /gamma/markets");
    return ResponseEntity.ok(gammaClient.markets(query, gammaHeaders(authorization, cookie)));
  }

  @GetMapping("/markets/{id}")
  public ResponseEntity<JsonNode> marketById(
      @PathVariable String id,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cookie", required = false) String cookie
  ) {
    log.info("api /gamma/markets id={}", id);
    return ResponseEntity.ok(gammaClient.marketById(id, gammaHeaders(authorization, cookie)));
  }

  @GetMapping("/events")
  public ResponseEntity<JsonNode> events(
      @RequestParam Map<String, String> query,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cookie", required = false) String cookie
  ) {
    log.info("api /gamma/events");
    return ResponseEntity.ok(gammaClient.events(query, gammaHeaders(authorization, cookie)));
  }

  @GetMapping("/events/{id}")
  public ResponseEntity<JsonNode> eventById(
      @PathVariable String id,
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestHeader(name = "Cookie", required = false) String cookie
  ) {
    log.info("api /gamma/events id={}", id);
    return ResponseEntity.ok(gammaClient.eventById(id, gammaHeaders(authorization, cookie)));
  }

  private static Map<String, String> gammaHeaders(String authorization, String cookie) {
    if (authorization != null && !authorization.isBlank() && cookie != null && !cookie.isBlank()) {
      return Map.of(
          "Authorization", authorization.trim(),
          "Cookie", cookie.trim()
      );
    }
    if (authorization != null && !authorization.isBlank()) {
      return Map.of("Authorization", authorization.trim());
    }
    if (cookie != null && !cookie.isBlank()) {
      return Map.of("Cookie", cookie.trim());
    }
    return Map.of();
  }
}
