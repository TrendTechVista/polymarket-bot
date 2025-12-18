package com.polybot.ingestor.web;

import com.polybot.hft.events.HftEventsProperties;
import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import com.polybot.ingestor.config.IngestorProperties;
import com.polybot.ingestor.config.MarketWsProperties;
import com.polybot.ingestor.ingest.PolymarketMarketContextIngestor;
import com.polybot.ingestor.ingest.PolymarketUpDownMarketWsIngestor;
import com.polybot.ingestor.ingest.PolymarketUserIngestor;
import com.polybot.ingestor.ingest.PolygonTxReceiptIngestor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestor")
@RequiredArgsConstructor
public class IngestorController {

  private final Environment env;
  private final IngestorProperties ingestorProperties;
  private final MarketWsProperties marketWsProperties;
  private final HftEventsProperties hftEventsProperties;
  private final PolymarketUserIngestor ingestor;
  private final PolymarketMarketContextIngestor marketContext;
  private final PolymarketUpDownMarketWsIngestor marketWsIngestor;
  private final ClobMarketWebSocketClient marketWsClient;
  private final PolygonTxReceiptIngestor polygonTxReceipts;

  @GetMapping("/status")
  public Status status() {
    return new Status(
        env.getProperty("spring.application.name"),
        env.getProperty("spring.profiles.active"),
        ingestorProperties.polymarket().username(),
        ingestorProperties.polymarket().proxyAddress(),
        ingestorProperties.polymarket().dataApiBaseUrl().toString(),
        ingestorProperties.polling().enabled(),
        ingestorProperties.polling().pollIntervalSeconds(),
        ingestorProperties.polling().pageSize(),
        ingestorProperties.polling().requestDelayMillis(),
        ingestorProperties.polling().backfillOnStart(),
        ingestorProperties.polling().backfillMaxPages(),
        ingestorProperties.marketContext().enabled(),
        ingestorProperties.marketContext().gammaApiBaseUrl().toString(),
        ingestorProperties.marketContext().clobRestBaseUrl().toString(),
        marketWsProperties.enabled(),
        marketWsProperties.discoveryIntervalSeconds(),
        marketWsIngestor.cycles(),
        marketWsIngestor.marketsDiscovered(),
        marketWsIngestor.slugFetchFailures(),
        marketWsClient.isStarted(),
        marketWsClient.subscribedAssetCount(),
        marketWsClient.topOfBookCount(),
        hftEventsProperties.enabled(),
        hftEventsProperties.topic(),
        ingestor.polls(),
        ingestor.publishedTrades(),
        ingestor.publishedPositionSnapshots(),
        ingestor.failures(),
        ingestor.lastPollAtMillis(),
        ingestor.lastPositionsSnapshotAtMillis(),
        ingestor.target(),
        marketContext.trackedMarkets(),
        marketContext.publishedGammaSnapshots(),
        marketContext.publishedClobTobs(),
        marketContext.publishedMarketTrades(),
        marketContext.gammaPolls(),
        marketContext.lastGammaPollAtMillis(),
        marketContext.failures(),
        polygonTxReceipts.polls(),
        polygonTxReceipts.publishedReceipts(),
        polygonTxReceipts.failures(),
        polygonTxReceipts.lastPollAtMillis(),
        polygonTxReceipts.queuedTxCount(),
        polygonTxReceipts.inFlightTxCount(),
        polygonTxReceipts.receiptWorkers(),
        polygonTxReceipts.blockTimestampCacheSize()
    );
  }

  public record Status(
      String app,
      String activeProfile,
      String configuredUsername,
      String configuredProxyAddress,
      String dataApiBaseUrl,
      boolean pollingEnabled,
      int pollIntervalSeconds,
      int pageSize,
      long requestDelayMillis,
      boolean backfillOnStart,
      Integer backfillMaxPages,
      boolean marketContextEnabled,
      String gammaApiBaseUrl,
      String clobRestBaseUrl,
      boolean marketWsEnabled,
      int marketWsDiscoveryIntervalSeconds,
      long marketWsCycles,
      long marketWsMarketsDiscovered,
      long marketWsSlugFetchFailures,
      boolean marketWsClientStarted,
      int marketWsSubscribedAssets,
      int marketWsTopOfBookAssets,
      boolean kafkaEventsEnabled,
      String kafkaTopic,
      long polls,
      long publishedTrades,
      long publishedPositionSnapshots,
      long failures,
      long lastPollAtMillis,
      long lastPositionsSnapshotAtMillis,
      PolymarketUserIngestor.TargetStatus target,
      int marketContextTrackedMarkets,
      long marketContextPublishedGammaSnapshots,
      long marketContextPublishedClobTobs,
      long marketContextPublishedMarketTrades,
      long marketContextGammaPolls,
      long marketContextLastGammaPollAtMillis,
      long marketContextFailures,
      long polygonTxPolls,
      long polygonTxPublishedReceipts,
      long polygonTxFailures,
      long polygonTxLastPollAtMillis,
      int polygonTxQueuedCount,
      int polygonTxInFlightCount,
      int polygonTxReceiptWorkers,
      int polygonTxBlockTimestampCacheSize
  ) {
  }
}
