package com.polybot.analytics.web;

import com.polybot.analytics.repo.UserTradeAnalyticsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics/users/{username}/trades")
@RequiredArgsConstructor
public class UserTradeAnalyticsController {

  private final UserTradeAnalyticsRepository repository;

  @GetMapping("/report")
  public UserTradeReport report(
      @PathVariable("username") String username,
      @RequestParam(name = "topMarkets", required = false, defaultValue = "20") int topMarkets
  ) {
    return new UserTradeReport(
        username,
        repository.stats(username),
        repository.sideBreakdown(username),
        repository.outcomeBreakdown(username),
        repository.upDown15mTiming(username),
        repository.completeSetStats(username),
        repository.realizedPnl(username),
        repository.topMarkets(username, topMarkets)
    );
  }

  @GetMapping("/complete-sets")
  public List<UserTradeAnalyticsRepository.CompleteSetMarket> completeSets(
      @PathVariable("username") String username,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.completeSetMarkets(username, limit);
  }

  @GetMapping("/complete-sets/detected")
  public UserTradeAnalyticsRepository.DetectedCompleteSetStats detectedCompleteSetStats(
      @PathVariable("username") String username,
      @RequestParam(name = "windowSeconds", required = false, defaultValue = "10") int windowSeconds
  ) {
    return repository.detectedCompleteSetStats(username, windowSeconds);
  }

  @GetMapping("/complete-sets/detected/markets")
  public List<UserTradeAnalyticsRepository.DetectedCompleteSetMarket> detectedCompleteSetMarkets(
      @PathVariable("username") String username,
      @RequestParam(name = "windowSeconds", required = false, defaultValue = "10") int windowSeconds,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.detectedCompleteSetMarkets(username, windowSeconds, limit);
  }

  @GetMapping("/execution")
  public UserTradeAnalyticsRepository.ExecutionQualityStats execution(
      @PathVariable("username") String username
  ) {
    return repository.executionQuality(username);
  }

  @GetMapping("/execution/report")
  public UserTradeAnalyticsRepository.ExecutionQualityReport executionReport(
      @PathVariable("username") String username
  ) {
    return repository.executionQualityReport(username);
  }

  @GetMapping("/pnl/markets")
  public List<UserTradeAnalyticsRepository.MarketPnl> pnlByMarket(
      @PathVariable("username") String username,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.realizedPnlByMarket(username, limit);
  }

  @GetMapping("/pnl/execution")
  public List<UserTradeAnalyticsRepository.ExecutionTypePnl> pnlByExecutionType(
      @PathVariable("username") String username
  ) {
    return repository.realizedPnlByExecutionType(username);
  }

  @GetMapping("/pnl/series")
  public List<UserTradeAnalyticsRepository.SeriesPnl> pnlBySeries(
      @PathVariable("username") String username
  ) {
    return repository.realizedPnlBySeries(username);
  }

  @GetMapping("/pnl/timing/updown-15m")
  public List<UserTradeAnalyticsRepository.TimingPnlBucket> pnlByTimingUpDown15m(
      @PathVariable("username") String username,
      @RequestParam(name = "bucketSeconds", required = false, defaultValue = "60") int bucketSeconds
  ) {
    return repository.upDown15mPnlByTimingBucket(username, bucketSeconds);
  }

  @GetMapping("/timing/updown-15m/buckets")
  public List<UserTradeAnalyticsRepository.TimingBucket> upDown15mTimingBuckets(
      @PathVariable("username") String username,
      @RequestParam(name = "bucketSeconds", required = false, defaultValue = "60") int bucketSeconds
  ) {
    return repository.upDown15mTimingBuckets(username, bucketSeconds);
  }

  @GetMapping("/activity/hourly")
  public List<UserTradeAnalyticsRepository.HourlyTradeActivity> hourlyActivity(
      @PathVariable("username") String username
  ) {
    return repository.hourlyTradeActivity(username);
  }

  @GetMapping("/selection/updown-15m/assets")
  public List<UserTradeAnalyticsRepository.UpDown15mAssetActivity> upDown15mAssets(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mAssetActivity(username);
  }

  @GetMapping("/selection/summary")
  public UserTradeAnalyticsRepository.MarketSelectionSummary selectionSummary(
      @PathVariable("username") String username
  ) {
    return repository.selectionSummary(username);
  }

  @GetMapping("/selection/series")
  public List<UserTradeAnalyticsRepository.SeriesActivity> selectionSeries(
      @PathVariable("username") String username
  ) {
    return repository.seriesActivity(username);
  }

  @GetMapping("/churn")
  public UserTradeAnalyticsRepository.MarketChurnStats churn(
      @PathVariable("username") String username
  ) {
    return repository.marketChurn(username);
  }

  @GetMapping("/timing/updown-15m/assets")
  public List<UserTradeAnalyticsRepository.UpDown15mAssetTimingQuantiles> upDown15mTimingByAsset(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mTimingQuantilesByAsset(username);
  }

  @GetMapping("/timing/updown-15m/outcomes")
  public List<UserTradeAnalyticsRepository.UpDown15mOutcomeTimingQuantiles> upDown15mTimingByOutcome(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mTimingQuantilesByOutcome(username);
  }

  @GetMapping("/timing/updown-15m/markets")
  public List<UserTradeAnalyticsRepository.UpDown15mMarketTimingQuantiles> upDown15mTimingByMarket(
      @PathVariable("username") String username,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
  ) {
    return repository.upDown15mTimingQuantilesByMarket(username, limit);
  }

  @GetMapping("/timing/updown-15m/daily-assets")
  public List<UserTradeAnalyticsRepository.UpDown15mDailyAssetTiming> upDown15mTimingDailyAssets(
      @PathVariable("username") String username
  ) {
    return repository.upDown15mDailyTimingByAsset(username);
  }

  public record UserTradeReport(
      String username,
      UserTradeAnalyticsRepository.UserTradeStats stats,
      List<UserTradeAnalyticsRepository.NamedCount> sideBreakdown,
      List<UserTradeAnalyticsRepository.NamedCount> outcomeBreakdown,
      UserTradeAnalyticsRepository.UpDown15mTimingStats upDown15mTiming,
      UserTradeAnalyticsRepository.CompleteSetStats completeSetStats,
      UserTradeAnalyticsRepository.RealizedPnlStats realizedPnl,
      List<UserTradeAnalyticsRepository.MarketCount> topMarkets
  ) {
  }
}
