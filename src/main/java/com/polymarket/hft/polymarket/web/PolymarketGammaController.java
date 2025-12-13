package com.polymarket.hft.polymarket.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.polymarket.hft.polymarket.gamma.PolymarketGammaClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/polymarket/gamma")
@RequiredArgsConstructor
public class PolymarketGammaController {

  private final PolymarketGammaClient gammaClient;

  @GetMapping("/search")
  public ResponseEntity<JsonNode> search(@RequestParam Map<String, String> query) {
    return ResponseEntity.ok(gammaClient.search(query));
  }

  @GetMapping("/markets")
  public ResponseEntity<JsonNode> markets(@RequestParam Map<String, String> query) {
    return ResponseEntity.ok(gammaClient.markets(query));
  }

  @GetMapping("/markets/{id}")
  public ResponseEntity<JsonNode> marketById(@PathVariable String id) {
    return ResponseEntity.ok(gammaClient.marketById(id));
  }

  @GetMapping("/events")
  public ResponseEntity<JsonNode> events(@RequestParam Map<String, String> query) {
    return ResponseEntity.ok(gammaClient.events(query));
  }

  @GetMapping("/events/{id}")
  public ResponseEntity<JsonNode> eventById(@PathVariable String id) {
    return ResponseEntity.ok(gammaClient.eventById(id));
  }
}

