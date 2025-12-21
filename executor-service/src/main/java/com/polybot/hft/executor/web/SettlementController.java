package com.polybot.hft.executor.web;

import com.polybot.hft.executor.settlement.ExecutorOnchainProperties;
import com.polybot.hft.executor.settlement.ExecutorSettlementProperties;
import com.polybot.hft.executor.settlement.PolymarketSettlementService;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/polymarket/settlement")
@Validated
@RequiredArgsConstructor
public class SettlementController {

  private final @NonNull PolymarketSettlementService settlementService;
  private final @NonNull ExecutorSettlementProperties settlementProperties;
  private final @NonNull ExecutorOnchainProperties onchainProperties;

  @GetMapping("/config")
  public ResponseEntity<Object> getConfig() {
    return ResponseEntity.ok(new ConfigResponse(settlementProperties, onchainProperties));
  }

  @GetMapping("/plan")
  public ResponseEntity<Object> plan() {
    return ResponseEntity.ok(settlementService.plan());
  }

  @PostMapping("/run")
  public ResponseEntity<Object> runOnce(
      @RequestParam(name = "dryRun", required = false) Boolean dryRun
  ) {
    return ResponseEntity.ok(settlementService.runOnce(dryRun));
  }

  private record ConfigResponse(
      @NotNull ExecutorSettlementProperties settlement,
      @NotNull ExecutorOnchainProperties onchain
  ) {
  }
}

