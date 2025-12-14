package com.polybot.ingestor.web;

import com.polybot.hft.events.HftEventsProperties;
import com.polybot.ingestor.config.IngestorProperties;
import com.polybot.ingestor.ingest.PolymarketUserIngestor;
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
  private final HftEventsProperties hftEventsProperties;
  private final PolymarketUserIngestor ingestor;

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
        hftEventsProperties.enabled(),
        hftEventsProperties.topic(),
        ingestor.polls(),
        ingestor.publishedTrades(),
        ingestor.publishedPositionSnapshots(),
        ingestor.failures(),
        ingestor.lastPollAtMillis(),
        ingestor.lastPositionsSnapshotAtMillis(),
        ingestor.target()
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
      boolean kafkaEventsEnabled,
      String kafkaTopic,
      long polls,
      long publishedTrades,
      long publishedPositionSnapshots,
      long failures,
      long lastPollAtMillis,
      long lastPositionsSnapshotAtMillis,
      PolymarketUserIngestor.TargetStatus target
  ) {
  }
}

