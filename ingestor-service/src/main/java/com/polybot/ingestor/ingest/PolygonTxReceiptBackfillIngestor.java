package com.polybot.ingestor.ingest;

import com.polybot.ingestor.config.ClickHouseProperties;
import com.polybot.ingestor.config.PolygonReceiptBackfillProperties;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ClickHouse-driven backfill for Polygon tx receipts.
 *
 * Motivation:
 * - Receipts are only fetched for tx hashes observed during runtime.
 * - When receipts collection is enabled later, we need a way to backfill historical tx hashes
 *   from ClickHouse without relying on Polymarket Data API paging limits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolygonTxReceiptBackfillIngestor {

  private static final Pattern ETH_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
  private static final Pattern TX_HASH = Pattern.compile("^0x[a-fA-F0-9]{64}$");

  private final @NonNull PolygonReceiptBackfillProperties backfill;
  private final @NonNull ClickHouseProperties clickhouse;
  private final @NonNull RestClient clickhouseRestClient;
  private final @NonNull PolymarketUserIngestor userIngestor;
  private final @NonNull PolygonTxReceiptIngestor receipts;
  private final @NonNull Clock clock;

  @Scheduled(
      initialDelayString = "15000",
      fixedDelayString = "${ingestor.polygon.backfill.interval-seconds:30}000"
  )
  public void pollMissingReceipts() {
    if (!backfill.enabled()) {
      return;
    }

    PolymarketUserIngestor.TargetStatus t = userIngestor.target();
    if (t == null || t.proxyAddress() == null || !ETH_ADDRESS.matcher(t.proxyAddress()).matches()) {
      return;
    }

    int batch = Math.max(1, backfill.batchSize());
    List<String> txs;
    try {
      txs = queryMissingTxHashes(t.proxyAddress(), batch);
    } catch (Exception e) {
      log.debug("polygon receipt backfill query failed: {}", e.toString());
      return;
    }

    if (txs.isEmpty()) {
      return;
    }

    int enqueued = 0;
    for (String tx : txs) {
      if (tx == null || !TX_HASH.matcher(tx).matches()) {
        continue;
      }
      receipts.enqueueBackfill(t.username(), t.proxyAddress(), tx);
      enqueued++;
      sleep(backfill.requestDelayMillis());
    }

    log.info("polygon receipt backfill queued={} proxyAddress={} at={}", enqueued, suffix(t.proxyAddress()), Instant.now(clock));
  }

  private List<String> queryMissingTxHashes(String proxyAddress, int limit) {
    String proxy = proxyAddress.trim().toLowerCase();
    String sql = """
        SELECT
          lower(transaction_hash) AS tx_hash,
          max(ts) AS last_seen
        FROM polybot.user_trades_dedup
        WHERE proxy_address = '%s'
          AND transaction_hash != ''
          AND lower(transaction_hash) NOT IN (
            SELECT tx_hash FROM polybot.polygon_tx_receipts_latest
          )
        GROUP BY tx_hash
        ORDER BY last_seen DESC
        LIMIT %d
        FORMAT TabSeparated
        """.formatted(proxy, Math.max(1, limit));

    String body = clickhouseRestClient.post()
        .uri(uriBuilder -> uriBuilder
            .path("/")
            .queryParam("database", clickhouse.database())
            .queryParam("user", clickhouse.user())
            .queryParam("password", clickhouse.password())
            .build())
        .body(sql)
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      return List.of();
    }

    List<String> out = new ArrayList<>();
    for (String line : body.split("\n")) {
      if (line == null || line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\t");
      if (parts.length == 0) {
        continue;
      }
      String tx = parts[0].trim();
      if (!tx.isEmpty()) {
        out.add(tx);
      }
    }
    return out;
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String suffix(String address) {
    if (address == null || address.length() < 10) {
      return String.valueOf(address);
    }
    return "..." + address.substring(address.length() - 6);
  }
}

