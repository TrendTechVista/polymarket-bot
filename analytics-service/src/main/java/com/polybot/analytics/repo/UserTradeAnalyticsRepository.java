package com.polybot.analytics.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface UserTradeAnalyticsRepository {

  UserTradeStats stats(String username);

  List<NamedCount> sideBreakdown(String username);

  List<NamedCount> outcomeBreakdown(String username);

  List<MarketCount> topMarkets(String username, int limit);

  UpDown15mTimingStats upDown15mTiming(String username);

  CompleteSetStats completeSetStats(String username);

  RealizedPnlStats realizedPnl(String username);

  List<CompleteSetMarket> completeSetMarkets(String username, int limit);

  ExecutionQualityStats executionQuality(String username);

  List<MarketPnl> realizedPnlByMarket(String username, int limit);

  List<TimingBucket> upDown15mTimingBuckets(String username, int bucketSeconds);

  DetectedCompleteSetStats detectedCompleteSetStats(String username, int windowSeconds);

  List<DetectedCompleteSetMarket> detectedCompleteSetMarkets(String username, int windowSeconds, int limit);

  List<TimingPnlBucket> upDown15mPnlByTimingBucket(String username, int bucketSeconds);

  List<HourlyTradeActivity> hourlyTradeActivity(String username);

  List<UpDown15mAssetActivity> upDown15mAssetActivity(String username);

  MarketSelectionSummary selectionSummary(String username);

  MarketChurnStats marketChurn(String username);

  List<SeriesActivity> seriesActivity(String username);

  List<UpDown15mAssetTimingQuantiles> upDown15mTimingQuantilesByAsset(String username);

  List<UpDown15mOutcomeTimingQuantiles> upDown15mTimingQuantilesByOutcome(String username);

  List<UpDown15mMarketTimingQuantiles> upDown15mTimingQuantilesByMarket(String username, int limit);

  List<UpDown15mDailyAssetTiming> upDown15mDailyTimingByAsset(String username);

  ExecutionQualityReport executionQualityReport(String username);

  List<ExecutionTypePnl> realizedPnlByExecutionType(String username);

  List<SeriesPnl> realizedPnlBySeries(String username);

  record UserTradeStats(
      long trades,
      Instant firstTradeAt,
      Instant lastTradeAt,
      long uniqueMarkets,
      long uniqueAssets,
      double notionalUsd,
      double avgPrice,
      double avgSize
  ) {
  }

  record NamedCount(String name, long count) {
  }

  record MarketCount(String slug, String title, long trades) {
  }

  record UpDown15mTimingStats(
      long trades,
      long minSecondsToEnd,
      long p10SecondsToEnd,
      long p50SecondsToEnd,
      long p90SecondsToEnd,
      long maxSecondsToEnd,
      double avgSecondsToEnd
  ) {
  }

  record CompleteSetStats(
      long marketsTraded,
      long marketsWithCompleteSets,
      double completeSetShares,
      double totalImpliedEdgeUsd,
      double avgImpliedEdgePerShare
  ) {
  }

  record CompleteSetMarket(
      String marketSlug,
      String title,
      double upShares,
      double downShares,
      double completeSetShares,
      double upAvgPrice,
      double downAvgPrice,
      double impliedEdgePerShare,
      double impliedEdgeUsd
  ) {
  }

  record ExecutionQualityStats(
      long trades,
      long tradesWithTob,
      double tobCoverage,
      long buyTakerLike,
      long buyMakerLike,
      long buyInside,
      double avgSpread,
      double avgPriceMinusMid
  ) {
  }

  record RealizedPnlStats(
      double realizedPnlUsd,
      long resolvedTrades,
      long resolvedMarkets,
      long totalMarkets,
      double winRate
  ) {
  }

  record MarketPnl(
      String marketSlug,
      String title,
      long resolvedTrades,
      double realizedPnlUsd,
      double winRate
  ) {
  }

  record TimingBucket(
      long bucketStartSecondsToEnd,
      long bucketEndSecondsToEnd,
      long trades,
      double avgPrice,
      double avgSize
  ) {
  }

  record DetectedCompleteSetStats(
      long marketsTraded,
      long marketsWithDetectedCompleteSets,
      long windowsWithBothSides,
      double detectedCompleteSetShares,
      double totalImpliedEdgeUsd,
      double avgImpliedEdgePerShare
  ) {
  }

  record DetectedCompleteSetMarket(
      String marketSlug,
      String title,
      long windowsWithBothSides,
      double detectedCompleteSetShares,
      double totalImpliedEdgeUsd,
      double avgImpliedEdgePerShare
  ) {
  }

  record TimingPnlBucket(
      long bucketStartSecondsToEnd,
      long bucketEndSecondsToEnd,
      long resolvedTrades,
      double realizedPnlUsd,
      double avgPnlPerTrade,
      double winRate
  ) {
  }

  record HourlyTradeActivity(
      int hourUtc,
      long trades,
      double notionalUsd
  ) {
  }

  record UpDown15mAssetActivity(
      String asset,
      long trades,
      double notionalUsd,
      double avgPrice,
      double avgSize
  ) {
  }

  record MarketSelectionSummary(
      long trades,
      long uniqueMarkets,
      long uniqueAssets,
      long top1Trades,
      double top1Share,
      long top5Trades,
      double top5Share,
      long top10Trades,
      double top10Share,
      double marketHhi
  ) {
  }

  record MarketChurnStats(
      long trades,
      long marketSwitches,
      double marketSwitchRate,
      double avgSecondsBetweenTrades,
      long p50SecondsBetweenTrades,
      long p90SecondsBetweenTrades
  ) {
  }

  record SeriesActivity(
      String series,
      long trades,
      double notionalUsd
  ) {
  }

  record UpDown15mAssetTimingQuantiles(
      String asset,
      long trades,
      long minSecondsToEnd,
      long p10SecondsToEnd,
      long p50SecondsToEnd,
      long p90SecondsToEnd,
      long maxSecondsToEnd,
      double avgSecondsToEnd
  ) {
  }

  record UpDown15mOutcomeTimingQuantiles(
      String outcome,
      long trades,
      long minSecondsToEnd,
      long p10SecondsToEnd,
      long p50SecondsToEnd,
      long p90SecondsToEnd,
      long maxSecondsToEnd,
      double avgSecondsToEnd
  ) {
  }

  record UpDown15mMarketTimingQuantiles(
      String marketSlug,
      String title,
      long trades,
      long minSecondsToEnd,
      long p10SecondsToEnd,
      long p50SecondsToEnd,
      long p90SecondsToEnd,
      long maxSecondsToEnd,
      double avgSecondsToEnd
  ) {
  }

  record UpDown15mDailyAssetTiming(
      LocalDate day,
      String asset,
      long trades,
      long p50SecondsToEnd,
      double avgSecondsToEnd
  ) {
  }

  record DistributionStats(
      double avg,
      double p10,
      double p50,
      double p90,
      double min,
      double max
  ) {
  }

  record SideExecutionReport(
      long tradesWithTob,
      long takerLike,
      long makerLike,
      long inside,
      DistributionStats costVsMid,
      DistributionStats effectiveSpread,
      DistributionStats effectiveSpreadToQuotedRatio
  ) {
  }

  record ExecutionQualityReport(
      long trades,
      long tradesWithTob,
      double tobCoverage,
      DistributionStats spread,
      DistributionStats tobLagMillis,
      SideExecutionReport buy,
      SideExecutionReport sell
  ) {
  }

  record ExecutionTypePnl(
      String side,
      String executionType,
      long resolvedTrades,
      double realizedPnlUsd,
      double avgPnlPerTrade,
      double winRate
  ) {
  }

  record SeriesPnl(
      String series,
      long resolvedTrades,
      double realizedPnlUsd,
      double avgPnlPerTrade,
      double winRate,
      double notionalUsd
  ) {
  }
}
