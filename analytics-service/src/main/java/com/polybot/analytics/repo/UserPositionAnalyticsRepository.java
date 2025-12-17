package com.polybot.analytics.repo;

import java.time.Instant;
import java.util.List;

public interface UserPositionAnalyticsRepository {

  PositionSummary summary(String username);

  List<TokenPosition> tokenPositions(String username, PositionState state, int limit);

  List<MarketPosition> marketPositions(String username, PositionState state, int limit);

  List<LedgerRow> ledger(String username, String marketSlug, String tokenId, int limit);

  List<UpDown15mAssetPnl> upDown15mRealizedPnlByAsset(String username);

  List<UpDown15mEntryBucketPnl> upDown15mRealizedPnlByEntryBucket(String username, int bucketSeconds);

  enum PositionState {
    ALL,
    OPEN,
    RESOLVED
  }

  record PositionSummary(
      long markets,
      long openMarkets,
      long resolvedMarkets,
      double openNetCostUsd,
      double openMtmPnlUsd,
      double realizedPnlUsd
  ) {
  }

  record TokenPosition(
      String marketSlug,
      String title,
      String tokenId,
      String outcome,
      long trades,
      Instant firstTradeAt,
      Instant lastTradeAt,
      double buyShares,
      double sellShares,
      double netShares,
      double buyCostUsd,
      double sellProceedsUsd,
      double netCostUsd,
      Instant latestTobAt,
      Double latestBestBidPrice,
      Double latestBestAskPrice,
      Double latestMid,
      Double latestSpread,
      Instant endDate,
      long secondsToEndNow,
      boolean resolved,
      Double settlePrice,
      Double realizedPnlUsd,
      Double mtmPnlUsd
  ) {
  }

  record MarketPosition(
      String marketSlug,
      String title,
      long trades,
      Instant firstTradeAt,
      Instant lastTradeAt,
      Instant endDate,
      long secondsToEndNow,
      boolean resolved,
      String resolvedOutcome,
      long tokenPositions,
      double netCostUsd,
      Double realizedPnlUsd,
      Double mtmPnlUsd
  ) {
  }

  record LedgerRow(
      Instant ts,
      String side,
      String outcome,
      double price,
      double size,
      double signedShares,
      double signedCostUsd,
      double positionShares,
      double positionCostUsd,
      Double avgEntryPrice,
      String transactionHash,
      String eventKey
  ) {
  }

  record UpDown15mAssetPnl(
      String asset,
      long tokenPositions,
      double netCostUsd,
      double realizedPnlUsd,
      double roi
  ) {
  }

  record UpDown15mEntryBucketPnl(
      long bucketStartSecondsToEnd,
      long bucketEndSecondsToEnd,
      long tokenPositions,
      double netCostUsd,
      double realizedPnlUsd,
      double roi
  ) {
  }

  // === New methods for complete-set and microstructure analysis ===

  CompleteSetSummary completeSetSummary(String username);

  List<CompleteSetPair> completeSetPairs(String username, int windowSeconds, int limit);

  MicrostructureSummary microstructureSummary(String username);

  record CompleteSetSummary(
      long totalPairs,
      long positiveEdgePairs,
      double totalMatchedShares,
      double totalEdgePnl,
      double avgEdgePerShare,
      double avgTimeGapSec,
      long uniqueMarkets
  ) {
  }

  record CompleteSetPair(
      String marketSlug,
      Instant ts1,
      String outcome1,
      double price1,
      double size1,
      Instant ts2,
      String outcome2,
      double price2,
      double size2,
      double matchedSize,
      double combinedCost,
      double edgePerShare,
      double edgePnl,
      long timeGapSec
  ) {
  }

  record MicrostructureSummary(
      long trades,
      Double avgVolume1mBefore,
      Double avgTradeCount1mBefore,
      Double avgVolume1mAfter,
      Double avgTimeSinceLastTradeMs,
      Double avgPriceRange1mBefore,
      double priorActivityCoverage
  ) {
  }
}
