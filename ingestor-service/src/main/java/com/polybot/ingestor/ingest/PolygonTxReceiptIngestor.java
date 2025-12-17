package com.polybot.ingestor.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.polybot.hft.events.HftEventPublisher;
import com.polybot.ingestor.config.PolygonProperties;
import com.polybot.ingestor.polygon.PolygonJsonRpcClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Enriches Polymarket trades with on-chain (Polygon) transaction receipts.
 *
 * Note: Polymarket orders are signed off-chain; receipts only reflect executed trades and other on-chain actions.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolygonTxReceiptIngestor {

  private static final String POLYGON_TX_RECEIPT_EVENT_TYPE = "polygon.tx.receipt";
  private static final int DEFAULT_SEEN_TX_CAPACITY = 250_000;
  private static final int CHAIN_ID_POLYGON = 137;

  private static final Pattern TX_HASH = Pattern.compile("^0x[a-fA-F0-9]{64}$");

  private final @NonNull PolygonProperties properties;
  private final @NonNull PolygonJsonRpcClient rpc;
  private final @NonNull HftEventPublisher events;
  private final @NonNull Clock clock;

  private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
  private final EvictingKeySet queued = new EvictingKeySet(DEFAULT_SEEN_TX_CAPACITY);
  private final EvictingKeySet processed = new EvictingKeySet(DEFAULT_SEEN_TX_CAPACITY);
  private final Map<String, Trigger> triggers = new ConcurrentHashMap<>();
  private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

  private final AtomicLong polls = new AtomicLong(0);
  private final AtomicLong publishedReceipts = new AtomicLong(0);
  private final AtomicLong failures = new AtomicLong(0);
  private volatile long lastPollAtMillis;

  public void onUserTrade(
      String username,
      String proxyAddress,
      String tradeKey,
      String transactionHash
  ) {
    if (!properties.enabled()) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }
    if (transactionHash == null || !TX_HASH.matcher(transactionHash).matches()) {
      return;
    }
    if (processed.contains(transactionHash)) {
      return;
    }

    triggers.put(transactionHash, new Trigger(username, proxyAddress, tradeKey));
    if (queued.add(transactionHash)) {
      queue.add(transactionHash);
    }
  }

  @Scheduled(
      initialDelayString = "5000",
      fixedDelayString = "${ingestor.polygon.poll-interval-millis:500}"
  )
  public void poll() {
    if (!properties.enabled()) {
      return;
    }
    if (!events.isEnabled()) {
      return;
    }

    polls.incrementAndGet();
    lastPollAtMillis = Instant.now(clock).toEpochMilli();

    int max = properties.maxReceiptsPerPoll();
    long delayMillis = properties.requestDelayMillis();

    int processedNow = 0;
    while (processedNow < max) {
      String txHash = queue.poll();
      if (txHash == null) {
        break;
      }
      processedNow++;
      tryFetchAndPublish(txHash);
      sleep(delayMillis);
    }
  }

  private void tryFetchAndPublish(String txHash) {
    if (txHash == null || txHash.isBlank()) {
      return;
    }
    if (processed.contains(txHash)) {
      return;
    }

    try {
      JsonNode receipt = rpc.getTransactionReceipt(txHash);
      if (receipt == null || receipt.isMissingNode() || receipt.isNull()) {
        requeue(txHash, "missing receipt");
        return;
      }

      String blockNumberHex = textOrNull(receipt.path("blockNumber"));
      if (blockNumberHex == null) {
        requeue(txHash, "missing blockNumber");
        return;
      }

      JsonNode block = rpc.getBlockByNumber(blockNumberHex);
      if (block == null || block.isMissingNode() || block.isNull()) {
        requeue(txHash, "missing block");
        return;
      }

      long blockTsSec = parseHexLong(textOrNull(block.path("timestamp")));
      Instant blockTs = blockTsSec > 0 ? Instant.ofEpochSecond(blockTsSec) : Instant.now(clock);

      long blockNumber = parseHexLong(blockNumberHex);
      int status = (int) parseHexLong(textOrNull(receipt.path("status")));
      long gasUsed = parseHexLong(textOrNull(receipt.path("gasUsed")));
      BigInteger effectiveGasPrice = parseHexBigInt(textOrNull(receipt.path("effectiveGasPrice")));

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("chainId", CHAIN_ID_POLYGON);
      data.put("txHash", txHash);
      data.put("blockNumber", blockNumber);
      data.put("blockTimestamp", blockTs.toString());
      data.put("status", status);
      data.put("from", textOrNull(receipt.path("from")));
      data.put("to", textOrNull(receipt.path("to")));
      data.put("gasUsed", gasUsed);
      if (effectiveGasPrice != null) {
        data.put("effectiveGasPrice", effectiveGasPrice.toString());
      }
      data.put("capturedAt", Instant.now(clock).toString());
      Trigger trigger = triggers.get(txHash);
      if (trigger != null) {
        data.put("trigger", Map.of(
            "username", trigger.username(),
            "proxyAddress", trigger.proxyAddress(),
            "tradeKey", trigger.tradeKey()
        ));
      }
      data.put("receipt", receipt);

      events.publish(blockTs, POLYGON_TX_RECEIPT_EVENT_TYPE, txHash, data);
      publishedReceipts.incrementAndGet();
      processed.add(txHash);
      attempts.remove(txHash);
      triggers.remove(txHash);
    } catch (Exception e) {
      failures.incrementAndGet();
      requeue(txHash, e.getMessage());
    }
  }

  private void requeue(String txHash, String reason) {
    int n = attempts.merge(txHash, 1, Integer::sum);
    if (n > 10) {
      log.debug("polygon receipt drop txHash={} attempts={} reason={}", txHash, n, reason);
      return;
    }
    queue.add(txHash);
  }

  private static long parseHexLong(String hex) {
    if (hex == null || hex.isBlank() || "0x".equals(hex)) {
      return 0L;
    }
    String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
    if (clean.isBlank()) {
      return 0L;
    }
    try {
      return new BigInteger(clean, 16).longValue();
    } catch (Exception e) {
      return 0L;
    }
  }

  private static BigInteger parseHexBigInt(String hex) {
    if (hex == null || hex.isBlank() || "0x".equals(hex)) {
      return null;
    }
    String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
    if (clean.isBlank()) {
      return null;
    }
    try {
      return new BigInteger(clean, 16);
    } catch (Exception e) {
      return null;
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    String v = node.asText(null);
    if (v == null || v.isBlank()) {
      return null;
    }
    return v;
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

  public long polls() {
    return polls.get();
  }

  public long publishedReceipts() {
    return publishedReceipts.get();
  }

  public long failures() {
    return failures.get();
  }

  public long lastPollAtMillis() {
    return lastPollAtMillis;
  }

  public int queuedTxCount() {
    return queued.size();
  }

  public record Trigger(
      String username,
      String proxyAddress,
      String tradeKey
  ) {
  }
}

