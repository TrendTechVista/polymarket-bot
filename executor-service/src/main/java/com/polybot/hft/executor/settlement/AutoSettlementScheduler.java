package com.polybot.hft.executor.settlement;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoSettlementScheduler {

  private final @NonNull ExecutorSettlementProperties settlementProperties;
  private final @NonNull PolymarketSettlementService settlementService;

  @Scheduled(fixedDelayString = "${executor.settlement.poll-interval-millis:30000}")
  public void tick() {
    if (!settlementProperties.enabled()) {
      return;
    }
    try {
      var res = settlementService.runOnce(null);
      if (!res.ok() && !"no-actions".equals(res.status())) {
        log.warn("auto-settlement run status={} ok={} dryRun={} planned={} txs={}",
            res.status(), res.ok(), res.dryRun(),
            res.plannedActions() == null ? 0 : res.plannedActions().size(),
            res.txs() == null ? 0 : res.txs().size());
      }
    } catch (Exception e) {
      log.warn("auto-settlement tick failed: {}", e.toString());
    }
  }
}

