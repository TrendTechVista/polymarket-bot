package com.polybot.analytics.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcUserTradeAnalyticsRepository implements UserTradeAnalyticsRepository {

  private static final double EPS = 1e-6;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public UserTradeStats stats(String username) {
    String sql = """
        SELECT
          count() AS trades,
          min(ts) AS first_trade_at,
          max(ts) AS last_trade_at,
          uniqExact(market_slug) AS unique_markets,
          uniqExact(token_id) AS unique_assets,
          sum(size * price) AS notional_usd,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
        """;
    return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> mapStats(rs), username);
  }

  @Override
  public List<NamedCount> sideBreakdown(String username) {
    String sql = """
        SELECT
          side,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY side
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), username);
  }

  @Override
  public List<NamedCount> outcomeBreakdown(String username) {
    String sql = """
        SELECT
          outcome,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY outcome
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new NamedCount(rs.getString(1), rs.getLong(2)), username);
  }

  @Override
  public List<MarketCount> topMarkets(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug AS slug,
          any(title) AS title,
          count() AS trades
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY market_slug
        ORDER BY trades DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new MarketCount(rs.getString(1), rs.getString(2), rs.getLong(3)), username);
  }

  @Override
  public UpDown15mTimingStats upDown15mTiming(String username) {
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end
        SELECT
          count() AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN -60 AND 3600
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new UpDown15mTimingStats(0, 0, 0, 0, 0, 0, 0);
      }
      return new UpDown15mTimingStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getLong(4),
          rs.getLong(5),
          rs.getLong(6),
          rs.getDouble(7)
      );
    }, username);
  }

  @Override
  public CompleteSetStats completeSetStats(String username) {
    String sql = """
        SELECT
          count() AS markets_traded,
          countIf(complete_set_shares > 0) AS markets_with_complete_sets,
          sum(complete_set_shares) AS complete_set_shares_total,
          sum(complete_set_shares * implied_edge_per_share) AS total_implied_edge_usd,
          if(sum(complete_set_shares) > 0, sum(complete_set_shares * implied_edge_per_share) / sum(complete_set_shares), 0) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            least(up_shares, down_shares) AS complete_set_shares,
            (1 - (up_avg_price + down_avg_price)) AS implied_edge_per_share
          FROM (
            SELECT
              market_slug,
              sumIf(size, outcome = 'Up') AS up_shares,
              sumIf(size * price, outcome = 'Up') AS up_cost,
              sumIf(size, outcome = 'Down') AS down_shares,
              sumIf(size * price, outcome = 'Down') AS down_cost,
              if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
              if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
            FROM user_trades_dedup
            WHERE username = ?
              AND side = 'BUY'
              AND outcome IN ('Up', 'Down')
            GROUP BY market_slug
          )
        )
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new CompleteSetStats(0, 0, 0, 0, 0);
      }
      return new CompleteSetStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getDouble(3),
          rs.getDouble(4),
          rs.getDouble(5)
      );
    }, username);
  }

  @Override
  public RealizedPnlStats realizedPnl(String username) {
    String sql = """
        SELECT
          sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
          countIf(realized_pnl IS NOT NULL) AS resolved_trades,
          uniqExactIf(market_slug, realized_pnl IS NOT NULL) AS resolved_markets,
          uniqExact(market_slug) AS total_markets,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            countIf(realized_pnl > 0) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS win_rate
        FROM user_trade_enriched
        WHERE username = ?
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new RealizedPnlStats(0, 0, 0, 0, 0);
      }
      return new RealizedPnlStats(
          rs.getDouble(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getLong(4),
          rs.getDouble(5)
      );
    }, username);
  }

  @Override
  public List<CompleteSetMarket> completeSetMarkets(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          title,
          up_shares,
          down_shares,
          least(up_shares, down_shares) AS complete_set_shares,
          up_avg_price,
          down_avg_price,
          (1 - (up_avg_price + down_avg_price)) AS implied_edge_per_share,
          least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price)) AS implied_edge_usd
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug
        )
        ORDER BY complete_set_shares DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new CompleteSetMarket(
        rs.getString(1),
        rs.getString(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6),
        rs.getDouble(7),
        rs.getDouble(8),
        rs.getDouble(9)
    ), username);
  }

  @Override
  public ExecutionQualityStats executionQuality(String username) {
    String sql = """
        WITH
          (tob_captured_at IS NOT NULL AND best_bid_price > 0 AND best_ask_price > 0) AS tob_known,
          (price >= best_ask_price - ?) AS buy_taker_like_flag,
          (price <= best_bid_price + ?) AS buy_maker_like_flag,
          (price > best_bid_price + ? AND price < best_ask_price - ?) AS buy_inside_flag
        SELECT
          count() AS trades,
          countIf(tob_known) AS trades_with_tob,
          if(count() > 0, countIf(tob_known) / count(), 0) AS tob_coverage,
          countIf(side = 'BUY' AND tob_known AND buy_taker_like_flag) AS buy_taker_like,
          countIf(side = 'BUY' AND tob_known AND buy_maker_like_flag) AS buy_maker_like,
          countIf(side = 'BUY' AND tob_known AND buy_inside_flag) AS buy_inside,
          avgIf(spread, tob_known) AS avg_spread,
          avgIf(price_minus_mid, tob_known) AS avg_price_minus_mid
        FROM user_trade_enriched
        WHERE username = ?
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new ExecutionQualityStats(0, 0, 0, 0, 0, 0, 0, 0);
      }
      return new ExecutionQualityStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getDouble(3),
          rs.getLong(4),
          rs.getLong(5),
          rs.getLong(6),
          rs.getDouble(7),
          rs.getDouble(8)
      );
    }, EPS, EPS, EPS, EPS, username);
  }

  @Override
  public List<MarketPnl> realizedPnlByMarket(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          title,
          resolved_trades,
          realized_pnl_usd,
          if(resolved_trades > 0, wins / resolved_trades, 0) AS win_rate
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            countIf(realized_pnl IS NOT NULL) AS resolved_trades,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
            countIf(realized_pnl > 0) AS wins
          FROM user_trade_enriched
          WHERE username = ?
          GROUP BY market_slug
        )
        WHERE resolved_trades > 0
        ORDER BY realized_pnl_usd DESC
        LIMIT %d
        """.formatted(safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new MarketPnl(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public List<TimingBucket> upDown15mTimingBuckets(String username, int bucketSeconds) {
    int safeBucketSeconds = Math.max(1, Math.min(900, bucketSeconds));
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          intDiv(seconds_to_end, %d) * %d AS bucket_start
        SELECT
          bucket_start AS bucket_start_seconds_to_end,
          (bucket_start + %d) AS bucket_end_seconds_to_end,
          count() AS trades,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN 0 AND 900
        GROUP BY bucket_start
        ORDER BY bucket_start ASC
        """.formatted(safeBucketSeconds, safeBucketSeconds, safeBucketSeconds);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new TimingBucket(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public DetectedCompleteSetStats detectedCompleteSetStats(String username, int windowSeconds) {
    int safeWindowSeconds = Math.max(1, Math.min(300, windowSeconds));
    String sql = """
        WITH
          (
            SELECT uniqExact(market_slug)
            FROM user_trades_dedup
            WHERE username = ?
              AND side = 'BUY'
              AND outcome IN ('Up', 'Down')
          ) AS markets_traded_total
        SELECT
          markets_traded_total AS markets_traded,
          uniqExact(market_slug) AS markets_with_detected_complete_sets,
          count() AS windows_with_both_sides,
          sum(least(up_shares, down_shares)) AS detected_complete_set_shares,
          sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) AS total_implied_edge_usd,
          if(
            sum(least(up_shares, down_shares)) > 0,
            sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) / sum(least(up_shares, down_shares)),
            0
          ) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            toStartOfInterval(ts, INTERVAL %d SECOND) AS bucket_start,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug, bucket_start
          HAVING up_shares > 0 AND down_shares > 0
        )
        """.formatted(safeWindowSeconds);

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new DetectedCompleteSetStats(0, 0, 0, 0, 0, 0);
      }
      return new DetectedCompleteSetStats(
          rs.getLong(1),
          rs.getLong(2),
          rs.getLong(3),
          rs.getDouble(4),
          rs.getDouble(5),
          rs.getDouble(6)
      );
    }, username, username);
  }

  @Override
  public List<DetectedCompleteSetMarket> detectedCompleteSetMarkets(String username, int windowSeconds, int limit) {
    int safeWindowSeconds = Math.max(1, Math.min(300, windowSeconds));
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        SELECT
          market_slug,
          any(title) AS title,
          count() AS windows_with_both_sides,
          sum(least(up_shares, down_shares)) AS detected_complete_set_shares,
          sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) AS total_implied_edge_usd,
          if(
            sum(least(up_shares, down_shares)) > 0,
            sum(least(up_shares, down_shares) * (1 - (up_avg_price + down_avg_price))) / sum(least(up_shares, down_shares)),
            0
          ) AS avg_implied_edge_per_share
        FROM (
          SELECT
            market_slug,
            any(title) AS title,
            toStartOfInterval(ts, INTERVAL %d SECOND) AS bucket_start,
            sumIf(size, outcome = 'Up') AS up_shares,
            sumIf(size * price, outcome = 'Up') AS up_cost,
            sumIf(size, outcome = 'Down') AS down_shares,
            sumIf(size * price, outcome = 'Down') AS down_cost,
            if(up_shares > 0, up_cost / up_shares, 0) AS up_avg_price,
            if(down_shares > 0, down_cost / down_shares, 0) AS down_avg_price
          FROM user_trades_dedup
          WHERE username = ?
            AND side = 'BUY'
            AND outcome IN ('Up', 'Down')
          GROUP BY market_slug, bucket_start
          HAVING up_shares > 0 AND down_shares > 0
        )
        GROUP BY market_slug
        ORDER BY detected_complete_set_shares DESC
        LIMIT %d
        """.formatted(safeWindowSeconds, safeLimit);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new DetectedCompleteSetMarket(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  @Override
  public List<TimingPnlBucket> upDown15mPnlByTimingBucket(String username, int bucketSeconds) {
    int safeBucketSeconds = Math.max(1, Math.min(900, bucketSeconds));
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          intDiv(seconds_to_end, %d) * %d AS bucket_start
        SELECT
          bucket_start AS bucket_start_seconds_to_end,
          (bucket_start + %d) AS bucket_end_seconds_to_end,
          countIf(realized_pnl IS NOT NULL) AS resolved_trades,
          sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS avg_pnl_per_trade,
          if(
            countIf(realized_pnl IS NOT NULL) > 0,
            countIf(realized_pnl > 0) / countIf(realized_pnl IS NOT NULL),
            0
          ) AS win_rate
        FROM user_trade_enriched
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN 0 AND 900
        GROUP BY bucket_start
        ORDER BY bucket_start ASC
        """.formatted(safeBucketSeconds, safeBucketSeconds, safeBucketSeconds);

    return jdbcTemplate.query(sql, (rs, rowNum) -> new TimingPnlBucket(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  @Override
  public List<HourlyTradeActivity> hourlyTradeActivity(String username) {
    String sql = """
        SELECT
          toHour(ts) AS hour_utc,
          count() AS trades,
          sum(size * price) AS notional_usd
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY hour_utc
        ORDER BY hour_utc ASC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new HourlyTradeActivity(
        rs.getInt(1),
        rs.getLong(2),
        rs.getDouble(3)
    ), username);
  }

  @Override
  public List<UpDown15mAssetActivity> upDown15mAssetActivity(String username) {
    String sql = """
        WITH upper(arrayElement(splitByChar('-', market_slug), 1)) AS asset
        SELECT
          asset,
          count() AS trades,
          sum(size * price) AS notional_usd,
          avg(price) AS avg_price,
          avg(size) AS avg_size
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND asset != ''
        GROUP BY asset
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mAssetActivity(
        rs.getString(1),
        rs.getLong(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public MarketSelectionSummary selectionSummary(String username) {
    String sql = """
        WITH
          totals AS (
            SELECT
              count() AS trades,
              uniqExact(market_slug) AS unique_markets,
              uniqExact(token_id) AS unique_assets
            FROM user_trades_dedup
            WHERE username = ?
          ),
          market_counts AS (
            SELECT
              market_slug,
              count() AS market_trades
            FROM user_trades_dedup
            WHERE username = ?
            GROUP BY market_slug
          ),
          ranked AS (
            SELECT
              market_trades,
              row_number() OVER (ORDER BY market_trades DESC) AS rn
            FROM market_counts
          )
        SELECT
          any(t.trades) AS trades,
          any(t.unique_markets) AS unique_markets,
          any(t.unique_assets) AS unique_assets,
          maxIf(r.market_trades, r.rn = 1) AS top1_trades,
          sumIf(r.market_trades, r.rn <= 5) AS top5_trades,
          sumIf(r.market_trades, r.rn <= 10) AS top10_trades,
          sum(pow(r.market_trades, 2)) / pow(if(any(t.trades) > 0, any(t.trades), 1), 2) AS market_hhi
        FROM ranked r
        CROSS JOIN totals t
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new MarketSelectionSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
      }
      long trades = rs.getLong("trades");
      long uniqueMarkets = rs.getLong("unique_markets");
      long uniqueAssets = rs.getLong("unique_assets");
      long top1Trades = rs.getLong("top1_trades");
      long top5Trades = rs.getLong("top5_trades");
      long top10Trades = rs.getLong("top10_trades");
      double marketHhi = rs.getDouble("market_hhi");

      double top1Share = trades > 0 ? ((double) top1Trades) / trades : 0;
      double top5Share = trades > 0 ? ((double) top5Trades) / trades : 0;
      double top10Share = trades > 0 ? ((double) top10Trades) / trades : 0;

      return new MarketSelectionSummary(
          trades,
          uniqueMarkets,
          uniqueAssets,
          top1Trades,
          top1Share,
          top5Trades,
          top5Share,
          top10Trades,
          top10Share,
          marketHhi
      );
    }, username, username);
  }

  @Override
  public MarketChurnStats marketChurn(String username) {
    String sql = """
        SELECT
          count() AS trades,
          countIf(rn > 1 AND market_slug != prev_market_slug) AS market_switches,
          if(
            count() > 1,
            countIf(rn > 1 AND market_slug != prev_market_slug) / (count() - 1),
            0
          ) AS market_switch_rate,
          avgIf(delta_seconds, rn > 1) AS avg_seconds_between_trades,
          quantileExactIf(0.50)(delta_seconds, rn > 1) AS p50_seconds_between_trades,
          quantileExactIf(0.90)(delta_seconds, rn > 1) AS p90_seconds_between_trades
        FROM (
          SELECT
            ts,
            market_slug,
            event_key,
            row_number() OVER (ORDER BY ts ASC, event_key ASC) AS rn,
            lagInFrame(ts) OVER (ORDER BY ts ASC, event_key ASC) AS prev_ts,
            lagInFrame(market_slug) OVER (ORDER BY ts ASC, event_key ASC) AS prev_market_slug,
            dateDiff('second', prev_ts, ts) AS delta_seconds
          FROM user_trades_dedup
          WHERE username = ?
        )
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new MarketChurnStats(0, 0, 0, 0, 0, 0);
      }
      return new MarketChurnStats(
          rs.getLong("trades"),
          rs.getLong("market_switches"),
          rs.getDouble("market_switch_rate"),
          rs.getDouble("avg_seconds_between_trades"),
          rs.getLong("p50_seconds_between_trades"),
          rs.getLong("p90_seconds_between_trades")
      );
    }, username);
  }

  @Override
  public List<SeriesActivity> seriesActivity(String username) {
    String sql = """
        WITH multiIf(
          position(market_slug, 'updown-15m-') > 0, 'updown-15m',
          position(lower(title), 'up or down') > 0, 'up-or-down',
          position(lower(market_slug), 'updown') > 0, 'updown',
          'other'
        ) AS series
        SELECT
          series,
          count() AS trades,
          sum(size * price) AS notional_usd
        FROM user_trades_dedup
        WHERE username = ?
        GROUP BY series
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new SeriesActivity(
        rs.getString(1),
        rs.getLong(2),
        rs.getDouble(3)
    ), username);
  }

  @Override
  public List<UpDown15mAssetTimingQuantiles> upDown15mTimingQuantilesByAsset(String username) {
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          upper(arrayElement(splitByChar('-', market_slug), 1)) AS asset
        SELECT
          asset,
          count() AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND asset != ''
          AND seconds_to_end BETWEEN -60 AND 900
        GROUP BY asset
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mAssetTimingQuantiles(
        rs.getString(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getLong(4),
        rs.getLong(5),
        rs.getLong(6),
        rs.getLong(7),
        rs.getDouble(8)
    ), username);
  }

  @Override
  public List<UpDown15mOutcomeTimingQuantiles> upDown15mTimingQuantilesByOutcome(String username) {
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end
        SELECT
          outcome,
          count() AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN -60 AND 900
        GROUP BY outcome
        ORDER BY trades DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mOutcomeTimingQuantiles(
        rs.getString(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getLong(4),
        rs.getLong(5),
        rs.getLong(6),
        rs.getLong(7),
        rs.getDouble(8)
    ), username);
  }

  @Override
  public List<UpDown15mMarketTimingQuantiles> upDown15mTimingQuantilesByMarket(String username, int limit) {
    int safeLimit = Math.max(1, Math.min(200, limit));
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end
        SELECT
          market_slug,
          any(title) AS title,
          count() AS trades,
          min(seconds_to_end) AS min_seconds_to_end,
          quantileExact(0.10)(seconds_to_end) AS p10_seconds_to_end,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          quantileExact(0.90)(seconds_to_end) AS p90_seconds_to_end,
          max(seconds_to_end) AS max_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND seconds_to_end BETWEEN -60 AND 900
        GROUP BY market_slug
        ORDER BY trades DESC
        LIMIT %d
        """.formatted(safeLimit);
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mMarketTimingQuantiles(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getLong(4),
        rs.getLong(5),
        rs.getLong(6),
        rs.getLong(7),
        rs.getLong(8),
        rs.getDouble(9)
    ), username);
  }

  @Override
  public List<UpDown15mDailyAssetTiming> upDown15mDailyTimingByAsset(String username) {
    String sql = """
        WITH
          toUInt32OrZero(arrayElement(splitByChar('-', market_slug), -1)) AS market_start_epoch,
          dateDiff('second', ts, toDateTime(market_start_epoch + 900)) AS seconds_to_end,
          upper(arrayElement(splitByChar('-', market_slug), 1)) AS asset,
          toDate(ts) AS day
        SELECT
          day,
          asset,
          count() AS trades,
          quantileExact(0.50)(seconds_to_end) AS p50_seconds_to_end,
          avg(seconds_to_end) AS avg_seconds_to_end
        FROM user_trades_dedup
        WHERE username = ?
          AND position(market_slug, 'updown-15m-') > 0
          AND market_start_epoch > 0
          AND asset != ''
          AND seconds_to_end BETWEEN 0 AND 900
        GROUP BY day, asset
        ORDER BY day ASC, asset ASC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new UpDown15mDailyAssetTiming(
        mapLocalDate(rs, 1),
        rs.getString(2),
        rs.getLong(3),
        rs.getLong(4),
        rs.getDouble(5)
    ), username);
  }

  @Override
  public ExecutionQualityReport executionQualityReport(String username) {
    String sql = """
        WITH
          (tob_captured_at IS NOT NULL AND best_bid_price > 0 AND best_ask_price > 0 AND mid > 0 AND spread > 0) AS tob_known,
          dateDiff('millisecond', ts, tob_captured_at) AS tob_lag_millis,
          (mid - price) AS buy_edge_vs_mid,
          (price - mid) AS sell_edge_vs_mid,
          (2 * abs(price - mid)) AS effective_spread,
          if(spread > 0, (2 * abs(price - mid)) / spread, CAST(NULL, 'Nullable(Float64)')) AS effective_spread_ratio,
          (side = 'BUY' AND price >= best_ask_price - ?) AS buy_taker_like_flag,
          (side = 'BUY' AND price <= best_bid_price + ?) AS buy_maker_like_flag,
          (side = 'BUY' AND price > best_bid_price + ? AND price < best_ask_price - ?) AS buy_inside_flag,
          (side = 'SELL' AND price <= best_bid_price + ?) AS sell_taker_like_flag,
          (side = 'SELL' AND price >= best_ask_price - ?) AS sell_maker_like_flag,
          (side = 'SELL' AND price > best_bid_price + ? AND price < best_ask_price - ?) AS sell_inside_flag
        SELECT
          count() AS trades,
          countIf(tob_known) AS trades_with_tob,
          if(count() > 0, countIf(tob_known) / count(), 0) AS tob_coverage,
          avgIf(spread, tob_known) AS spread_avg,
          quantileTDigestIf(0.10)(spread, tob_known) AS spread_p10,
          quantileTDigestIf(0.50)(spread, tob_known) AS spread_p50,
          quantileTDigestIf(0.90)(spread, tob_known) AS spread_p90,
          minIf(spread, tob_known) AS spread_min,
          maxIf(spread, tob_known) AS spread_max,
          avgIf(tob_lag_millis, tob_known) AS tob_lag_avg,
          quantileTDigestIf(0.10)(tob_lag_millis, tob_known) AS tob_lag_p10,
          quantileTDigestIf(0.50)(tob_lag_millis, tob_known) AS tob_lag_p50,
          quantileTDigestIf(0.90)(tob_lag_millis, tob_known) AS tob_lag_p90,
          minIf(tob_lag_millis, tob_known) AS tob_lag_min,
          maxIf(tob_lag_millis, tob_known) AS tob_lag_max,
          countIf(tob_known AND side = 'BUY') AS buy_trades_with_tob,
          countIf(tob_known AND buy_taker_like_flag) AS buy_taker_like,
          countIf(tob_known AND buy_maker_like_flag) AS buy_maker_like,
          countIf(tob_known AND buy_inside_flag) AS buy_inside,
          avgIf(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_avg,
          quantileTDigestIf(0.10)(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_p10,
          quantileTDigestIf(0.50)(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_p50,
          quantileTDigestIf(0.90)(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_p90,
          minIf(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_min,
          maxIf(buy_edge_vs_mid, tob_known AND side = 'BUY') AS buy_edge_max,
          avgIf(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_avg,
          quantileTDigestIf(0.10)(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_p10,
          quantileTDigestIf(0.50)(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_p50,
          quantileTDigestIf(0.90)(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_p90,
          minIf(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_min,
          maxIf(effective_spread, tob_known AND side = 'BUY') AS buy_eff_spread_max,
          avgIf(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_avg,
          quantileTDigestIf(0.10)(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_p10,
          quantileTDigestIf(0.50)(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_p50,
          quantileTDigestIf(0.90)(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_p90,
          minIf(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_min,
          maxIf(effective_spread_ratio, tob_known AND side = 'BUY' AND effective_spread_ratio IS NOT NULL) AS buy_eff_ratio_max,
          countIf(tob_known AND side = 'SELL') AS sell_trades_with_tob,
          countIf(tob_known AND sell_taker_like_flag) AS sell_taker_like,
          countIf(tob_known AND sell_maker_like_flag) AS sell_maker_like,
          countIf(tob_known AND sell_inside_flag) AS sell_inside,
          avgIf(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_avg,
          quantileTDigestIf(0.10)(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_p10,
          quantileTDigestIf(0.50)(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_p50,
          quantileTDigestIf(0.90)(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_p90,
          minIf(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_min,
          maxIf(sell_edge_vs_mid, tob_known AND side = 'SELL') AS sell_edge_max,
          avgIf(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_avg,
          quantileTDigestIf(0.10)(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_p10,
          quantileTDigestIf(0.50)(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_p50,
          quantileTDigestIf(0.90)(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_p90,
          minIf(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_min,
          maxIf(effective_spread, tob_known AND side = 'SELL') AS sell_eff_spread_max,
          avgIf(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_avg,
          quantileTDigestIf(0.10)(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_p10,
          quantileTDigestIf(0.50)(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_p50,
          quantileTDigestIf(0.90)(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_p90,
          minIf(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_min,
          maxIf(effective_spread_ratio, tob_known AND side = 'SELL' AND effective_spread_ratio IS NOT NULL) AS sell_eff_ratio_max
        FROM user_trade_enriched
        WHERE username = ?
        """;

    return jdbcTemplate.query(sql, rs -> {
      if (!rs.next()) {
        return new ExecutionQualityReport(
            0,
            0,
            0,
            emptyDistribution(),
            emptyDistribution(),
            emptySideExecutionReport(),
            emptySideExecutionReport()
        );
      }

      long trades = rs.getLong("trades");
      long tradesWithTob = rs.getLong("trades_with_tob");
      double tobCoverage = rs.getDouble("tob_coverage");

      DistributionStats spread = new DistributionStats(
          rs.getDouble("spread_avg"),
          rs.getDouble("spread_p10"),
          rs.getDouble("spread_p50"),
          rs.getDouble("spread_p90"),
          rs.getDouble("spread_min"),
          rs.getDouble("spread_max")
      );

      DistributionStats tobLagMillis = new DistributionStats(
          rs.getDouble("tob_lag_avg"),
          rs.getDouble("tob_lag_p10"),
          rs.getDouble("tob_lag_p50"),
          rs.getDouble("tob_lag_p90"),
          rs.getDouble("tob_lag_min"),
          rs.getDouble("tob_lag_max")
      );

      SideExecutionReport buy = new SideExecutionReport(
          rs.getLong("buy_trades_with_tob"),
          rs.getLong("buy_taker_like"),
          rs.getLong("buy_maker_like"),
          rs.getLong("buy_inside"),
          new DistributionStats(
              rs.getDouble("buy_edge_avg"),
              rs.getDouble("buy_edge_p10"),
              rs.getDouble("buy_edge_p50"),
              rs.getDouble("buy_edge_p90"),
              rs.getDouble("buy_edge_min"),
              rs.getDouble("buy_edge_max")
          ),
          new DistributionStats(
              rs.getDouble("buy_eff_spread_avg"),
              rs.getDouble("buy_eff_spread_p10"),
              rs.getDouble("buy_eff_spread_p50"),
              rs.getDouble("buy_eff_spread_p90"),
              rs.getDouble("buy_eff_spread_min"),
              rs.getDouble("buy_eff_spread_max")
          ),
          new DistributionStats(
              rs.getDouble("buy_eff_ratio_avg"),
              rs.getDouble("buy_eff_ratio_p10"),
              rs.getDouble("buy_eff_ratio_p50"),
              rs.getDouble("buy_eff_ratio_p90"),
              rs.getDouble("buy_eff_ratio_min"),
              rs.getDouble("buy_eff_ratio_max")
          )
      );

      SideExecutionReport sell = new SideExecutionReport(
          rs.getLong("sell_trades_with_tob"),
          rs.getLong("sell_taker_like"),
          rs.getLong("sell_maker_like"),
          rs.getLong("sell_inside"),
          new DistributionStats(
              rs.getDouble("sell_edge_avg"),
              rs.getDouble("sell_edge_p10"),
              rs.getDouble("sell_edge_p50"),
              rs.getDouble("sell_edge_p90"),
              rs.getDouble("sell_edge_min"),
              rs.getDouble("sell_edge_max")
          ),
          new DistributionStats(
              rs.getDouble("sell_eff_spread_avg"),
              rs.getDouble("sell_eff_spread_p10"),
              rs.getDouble("sell_eff_spread_p50"),
              rs.getDouble("sell_eff_spread_p90"),
              rs.getDouble("sell_eff_spread_min"),
              rs.getDouble("sell_eff_spread_max")
          ),
          new DistributionStats(
              rs.getDouble("sell_eff_ratio_avg"),
              rs.getDouble("sell_eff_ratio_p10"),
              rs.getDouble("sell_eff_ratio_p50"),
              rs.getDouble("sell_eff_ratio_p90"),
              rs.getDouble("sell_eff_ratio_min"),
              rs.getDouble("sell_eff_ratio_max")
          )
      );

      return new ExecutionQualityReport(trades, tradesWithTob, tobCoverage, spread, tobLagMillis, buy, sell);
    }, EPS, EPS, EPS, EPS, EPS, EPS, EPS, EPS, username);
  }

  @Override
  public List<ExecutionTypePnl> realizedPnlByExecutionType(String username) {
    String sql = """
        SELECT
          side,
          exec_type,
          resolved_trades,
          realized_pnl_usd,
          if(resolved_trades > 0, realized_pnl_usd / resolved_trades, 0) AS avg_pnl_per_trade,
          if(resolved_trades > 0, wins / resolved_trades, 0) AS win_rate
        FROM (
          WITH
            (tob_captured_at IS NOT NULL AND best_bid_price > 0 AND best_ask_price > 0) AS tob_known,
            multiIf(
              side = 'BUY' AND price >= best_ask_price - ?, 'TAKER_LIKE',
              side = 'BUY' AND price <= best_bid_price + ?, 'MAKER_LIKE',
              side = 'SELL' AND price <= best_bid_price + ?, 'TAKER_LIKE',
              side = 'SELL' AND price >= best_ask_price - ?, 'MAKER_LIKE',
              price > best_bid_price + ? AND price < best_ask_price - ?, 'INSIDE',
              'OUTSIDE'
            ) AS exec_type
          SELECT
            side,
            exec_type,
            countIf(realized_pnl IS NOT NULL) AS resolved_trades,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
            countIf(realized_pnl > 0) AS wins
          FROM user_trade_enriched
          WHERE username = ?
            AND tob_known
          GROUP BY side, exec_type
        )
        WHERE resolved_trades > 0
        ORDER BY realized_pnl_usd DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new ExecutionTypePnl(
        rs.getString(1),
        rs.getString(2),
        rs.getLong(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), EPS, EPS, EPS, EPS, EPS, EPS, username);
  }

  @Override
  public List<SeriesPnl> realizedPnlBySeries(String username) {
    String sql = """
        SELECT
          series,
          resolved_trades,
          realized_pnl_usd,
          if(resolved_trades > 0, realized_pnl_usd / resolved_trades, 0) AS avg_pnl_per_trade,
          if(resolved_trades > 0, wins / resolved_trades, 0) AS win_rate,
          notional_usd
        FROM (
          WITH multiIf(
            position(market_slug, 'updown-15m-') > 0, 'updown-15m',
            position(lower(title), 'up or down') > 0, 'up-or-down',
            position(lower(market_slug), 'updown') > 0, 'updown',
            'other'
          ) AS series
          SELECT
            series,
            countIf(realized_pnl IS NOT NULL) AS resolved_trades,
            sumIf(realized_pnl, realized_pnl IS NOT NULL) AS realized_pnl_usd,
            countIf(realized_pnl > 0) AS wins,
            sum(size * price) AS notional_usd
          FROM user_trade_enriched
          WHERE username = ?
          GROUP BY series
        )
        ORDER BY realized_pnl_usd DESC
        """;
    return jdbcTemplate.query(sql, (rs, rowNum) -> new SeriesPnl(
        rs.getString(1),
        rs.getLong(2),
        rs.getDouble(3),
        rs.getDouble(4),
        rs.getDouble(5),
        rs.getDouble(6)
    ), username);
  }

  private static UserTradeStats mapStats(ResultSet rs) {
    try {
      long trades = rs.getLong(1);
      Instant firstTradeAt = rs.getTimestamp(2).toInstant();
      Instant lastTradeAt = rs.getTimestamp(3).toInstant();
      long uniqueMarkets = rs.getLong(4);
      long uniqueAssets = rs.getLong(5);
      double notionalUsd = rs.getDouble(6);
      double avgPrice = rs.getDouble(7);
      double avgSize = rs.getDouble(8);
      return new UserTradeStats(trades, firstTradeAt, lastTradeAt, uniqueMarkets, uniqueAssets, notionalUsd, avgPrice, avgSize);
    } catch (Exception e) {
      throw new RuntimeException("Failed to map trade stats row", e);
    }
  }

  private static LocalDate mapLocalDate(ResultSet rs, int columnIndex) {
    try {
      Date date = rs.getDate(columnIndex);
      if (date == null) {
        return null;
      }
      return date.toLocalDate();
    } catch (Exception e) {
      throw new RuntimeException("Failed to map date column index=" + columnIndex, e);
    }
  }

  private static DistributionStats emptyDistribution() {
    return new DistributionStats(0, 0, 0, 0, 0, 0);
  }

  private static SideExecutionReport emptySideExecutionReport() {
    return new SideExecutionReport(0, 0, 0, 0, emptyDistribution(), emptyDistribution(), emptyDistribution());
  }
}
