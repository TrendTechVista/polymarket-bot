-- =============================================================================
-- DATA QUALITY PIPELINE
-- =============================================================================
-- Purpose: Create clean, validated datasets for strategy backtesting
--
-- Data Quality Requirements:
--   1. Must have both UP and DOWN token TOB at trade time
--   2. Must have valid seconds_to_end (>= 0)
--   3. Must be a valid UP/DOWN market series
--   4. Must have resolution data (for PnL calculation)
--   5. TOB lag must be < 5 seconds (fresh data)
--
-- Components:
--   - user_trade_clean: Only validated gabagool22 trades with dual-side TOB
--   - data_quality_metrics: Real-time quality monitoring
--   - data_quality_by_day: Daily quality trends
-- =============================================================================


-- =============================================================================
-- 1) CLEAN TRADES TABLE
-- =============================================================================
-- Only trades that meet ALL quality requirements

CREATE TABLE IF NOT EXISTS polybot.user_trade_clean
(
    ts DateTime64(3),
    username LowCardinality(String),
    market_slug String,
    series LowCardinality(String),

    -- Trade details
    token_id String,
    other_token_id String,
    outcome LowCardinality(String),
    side LowCardinality(String),
    price Float64,
    size Float64,
    seconds_to_end Int64,

    -- Our side TOB (the traded token)
    our_best_bid Float64,
    our_best_bid_size Float64,
    our_best_ask Float64,
    our_best_ask_size Float64,
    our_mid Float64,
    our_tob_lag_ms Int64,

    -- Other side TOB (the paired token)
    other_best_bid Float64,
    other_best_bid_size Float64,
    other_best_ask Float64,
    other_best_ask_size Float64,
    other_mid Float64,
    other_tob_lag_ms Int64,

    -- Complete-set edge
    complete_set_edge Float64,

    -- Resolution data
    is_resolved UInt8,
    settle_price Float64,
    realized_pnl Float64,

    -- Data quality flags
    tob_source LowCardinality(String),  -- 'WS' or 'REST'

    -- Keys
    event_key String,
    ingested_at DateTime64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toDate(ts)
ORDER BY (username, market_slug, ts, event_key);


-- =============================================================================
-- 2) MATERIALIZED VIEW: Auto-populate clean trades
-- =============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.user_trade_clean_mv
TO polybot.user_trade_clean
AS
WITH
    -- Get the other token's TOB via ASOF join
    trades_with_other AS (
        SELECT
            t.ts,
            t.username,
            t.market_slug,

            -- Series classification
            multiIf(
                t.market_slug LIKE 'btc-updown-15m-%', 'btc-15m',
                t.market_slug LIKE 'eth-updown-15m-%', 'eth-15m',
                t.market_slug LIKE 'bitcoin-up-or-down-%', 'btc-1h',
                t.market_slug LIKE 'ethereum-up-or-down-%', 'eth-1h',
                'other'
            ) AS series,

            t.token_id,
            t.token_ids,
            if(t.outcome = 'Up',
               arrayElement(t.token_ids, 2),
               arrayElement(t.token_ids, 1)
            ) AS other_token_id,
            t.outcome,
            t.side,
            t.price,
            t.size,
            t.seconds_to_end,

            -- Our side TOB (prefer WS)
            coalesce(t.ws_best_bid_price, t.best_bid_price) AS our_best_bid,
            coalesce(t.ws_best_bid_size, t.best_bid_size) AS our_best_bid_size,
            coalesce(t.ws_best_ask_price, t.best_ask_price) AS our_best_ask,
            coalesce(t.ws_best_ask_size, t.best_ask_size) AS our_best_ask_size,
            coalesce(t.ws_mid, t.mid) AS our_mid,
            coalesce(t.ws_tob_lag_millis,
                     toInt64(dateDiff('millisecond', t.ts, t.tob_captured_at))) AS our_tob_lag_ms,

            -- Other side TOB from WS
            o.best_bid_price AS other_best_bid,
            o.best_bid_size AS other_best_bid_size,
            o.best_ask_price AS other_best_ask,
            o.best_ask_size AS other_best_ask_size,
            (o.best_bid_price + o.best_ask_price) / 2 AS other_mid,
            toInt64(dateDiff('millisecond', o.ts, t.ts)) AS other_tob_lag_ms,

            t.is_resolved,
            t.settle_price,
            t.realized_pnl,

            if(t.ws_best_bid_price > 0, 'WS', 'REST') AS tob_source,

            t.event_key,
            now() AS ingested_at

        FROM polybot.user_trade_enriched_v3 t
        ASOF LEFT JOIN polybot.market_ws_tob o
            ON if(t.outcome = 'Up',
                  arrayElement(t.token_ids, 2),
                  arrayElement(t.token_ids, 1)
               ) = o.asset_id
            AND t.ts >= o.ts
        WHERE t.username = 'gabagool22'
          AND (t.market_slug LIKE '%updown%' OR t.market_slug LIKE '%up-or-down%')
    )
SELECT
    ts,
    username,
    market_slug,
    series,
    token_id,
    other_token_id,
    outcome,
    side,
    price,
    size,
    seconds_to_end,
    our_best_bid,
    our_best_bid_size,
    our_best_ask,
    our_best_ask_size,
    our_mid,
    our_tob_lag_ms,
    other_best_bid,
    other_best_bid_size,
    other_best_ask,
    other_best_ask_size,
    other_mid,
    other_tob_lag_ms,

    -- Calculate complete-set edge
    if(outcome = 'Up',
       1.0 - our_best_bid - other_best_bid,
       1.0 - other_best_bid - our_best_bid
    ) AS complete_set_edge,

    is_resolved,
    settle_price,
    realized_pnl,
    tob_source,
    event_key,
    ingested_at

FROM trades_with_other

-- QUALITY FILTERS
WHERE series != 'other'                              -- Valid series
  AND seconds_to_end IS NOT NULL                     -- Has time-to-end
  AND seconds_to_end >= 0                            -- Valid time
  AND our_best_bid > 0                               -- Has our TOB
  AND our_best_ask > 0
  AND other_best_bid > 0                             -- Has other TOB
  AND other_best_ask > 0
  AND abs(our_tob_lag_ms) < 5000                     -- Fresh our TOB (< 5s)
  AND abs(other_tob_lag_ms) < 5000                   -- Fresh other TOB (< 5s)
  AND length(token_ids) = 2;                         -- Has both token IDs


-- =============================================================================
-- 3) DATA QUALITY METRICS VIEW
-- =============================================================================
-- Real-time monitoring of data quality

CREATE OR REPLACE VIEW polybot.data_quality_metrics AS
WITH
    raw_counts AS (
        SELECT
            count() AS total_raw,
            countIf(market_slug LIKE '%updown%' OR market_slug LIKE '%up-or-down%') AS updown_markets,
            countIf(seconds_to_end IS NOT NULL AND seconds_to_end >= 0) AS has_time_to_end,
            countIf(length(token_ids) = 2) AS has_both_tokens,
            countIf(coalesce(ws_best_bid_price, best_bid_price) > 0) AS has_our_tob,
            countIf(ws_best_bid_price > 0) AS has_ws_tob,
            countIf(is_resolved = 1) AS is_resolved
        FROM polybot.user_trade_enriched_v3
        WHERE username = 'gabagool22'
    ),
    clean_counts AS (
        SELECT count() AS total_clean
        FROM polybot.user_trade_clean
        WHERE username = 'gabagool22'
    )
SELECT
    r.total_raw,
    r.updown_markets,
    r.has_time_to_end,
    r.has_both_tokens,
    r.has_our_tob,
    r.has_ws_tob,
    r.is_resolved,
    c.total_clean,
    round(c.total_clean * 100.0 / nullif(r.total_raw, 0), 2) AS clean_rate_pct,
    r.total_raw - c.total_clean AS rejected_count
FROM raw_counts r, clean_counts c;


-- =============================================================================
-- 4) DATA QUALITY BY DAY
-- =============================================================================
-- Track quality trends over time

CREATE OR REPLACE VIEW polybot.data_quality_by_day AS
WITH
    raw_by_day AS (
        SELECT
            toDate(ts) AS day,
            count() AS raw_count,
            countIf(ws_best_bid_price > 0) AS ws_tob_count
        FROM polybot.user_trade_enriched_v3
        WHERE username = 'gabagool22'
          AND (market_slug LIKE '%updown%' OR market_slug LIKE '%up-or-down%')
        GROUP BY day
    ),
    clean_by_day AS (
        SELECT
            toDate(ts) AS day,
            count() AS clean_count
        FROM polybot.user_trade_clean
        WHERE username = 'gabagool22'
        GROUP BY day
    )
SELECT
    r.day,
    r.raw_count,
    r.ws_tob_count,
    coalesce(c.clean_count, 0) AS clean_count,
    round(coalesce(c.clean_count, 0) * 100.0 / nullif(r.raw_count, 0), 2) AS clean_rate_pct,
    round(r.ws_tob_count * 100.0 / nullif(r.raw_count, 0), 2) AS ws_coverage_pct
FROM raw_by_day r
LEFT JOIN clean_by_day c ON r.day = c.day
ORDER BY r.day DESC;


-- =============================================================================
-- 5) BACKTEST-READY VIEW
-- =============================================================================
-- Clean data optimized for strategy backtesting

CREATE OR REPLACE VIEW polybot.backtest_ready AS
SELECT
    ts,
    market_slug,
    series,
    token_id,
    other_token_id,
    outcome,
    side,
    price,
    size,
    seconds_to_end,

    -- Book state
    our_best_bid,
    our_best_bid_size,
    our_best_ask,
    our_best_ask_size,
    our_mid,
    other_best_bid,
    other_best_bid_size,
    other_best_ask,
    other_best_ask_size,
    other_mid,
    complete_set_edge,

    -- Resolution
    is_resolved,
    settle_price,
    realized_pnl,

    -- Quality metrics
    our_tob_lag_ms,
    other_tob_lag_ms,
    tob_source

FROM polybot.user_trade_clean
WHERE username = 'gabagool22'
ORDER BY ts;


-- =============================================================================
-- 6) STRATEGY VALIDATION ON CLEAN DATA
-- =============================================================================
-- Re-run strategy validation using only clean data

CREATE OR REPLACE VIEW polybot.strategy_validation_clean AS
WITH
    base_sizes AS (
        SELECT 'btc-15m' AS series, 19.0 AS base_shares
        UNION ALL SELECT 'eth-15m', 14.0
        UNION ALL SELECT 'btc-1h', 18.0
        UNION ALL SELECT 'eth-1h', 14.0
    ),
    decisions AS (
        SELECT
            t.*,
            b.base_shares,

            -- Time window check
            t.seconds_to_end >= 0 AND t.seconds_to_end <= 3600 AS in_time_window,

            -- Edge check (1% minimum)
            t.complete_set_edge >= 0.01 AS has_sufficient_edge,

            -- Our quote would be at best bid
            t.our_best_bid AS our_quote_price,

            -- Would we quote?
            (t.seconds_to_end >= 0 AND t.seconds_to_end <= 3600
             AND t.complete_set_edge >= 0.01
            ) AS would_quote,

            -- Would we fill? (gabagool filled at or better than our quote)
            t.price <= t.our_best_bid + 0.01 AS likely_would_fill

        FROM polybot.user_trade_clean t
        LEFT JOIN base_sizes b ON t.series = b.series
        WHERE t.username = 'gabagool22'
    )
SELECT
    ts,
    market_slug,
    series,
    outcome,
    price AS actual_price,
    size AS actual_size,
    our_quote_price,
    base_shares AS our_quote_size,
    complete_set_edge,
    seconds_to_end,

    in_time_window,
    has_sufficient_edge,
    would_quote,
    likely_would_fill,

    -- Match classification
    multiIf(
        would_quote AND likely_would_fill, 'MATCH',
        would_quote AND NOT likely_would_fill, 'WOULD_QUOTE_NO_FILL',
        NOT in_time_window, 'OUTSIDE_TIME_WINDOW',
        NOT has_sufficient_edge, 'INSUFFICIENT_EDGE',
        'UNKNOWN'
    ) AS match_type,

    -- Simulated PnL
    if(would_quote AND likely_would_fill,
       (settle_price - our_quote_price) * base_shares,
       0
    ) AS simulated_pnl,

    realized_pnl AS actual_pnl,

    -- Data quality
    our_tob_lag_ms,
    other_tob_lag_ms,
    tob_source

FROM decisions;


-- =============================================================================
-- 7) CLEAN DATA REPLICATION SCORE
-- =============================================================================

CREATE OR REPLACE VIEW polybot.replication_score_clean AS
SELECT
    count() AS total_clean_trades,
    countIf(would_quote) AS we_would_quote,
    countIf(match_type = 'MATCH') AS we_would_match,

    round(countIf(would_quote) * 100.0 / count(), 2) AS quote_rate_pct,
    round(countIf(match_type = 'MATCH') * 100.0 / count(), 2) AS match_rate_pct,
    round(countIf(match_type = 'MATCH') * 100.0 / nullif(countIf(would_quote), 0), 2) AS fill_rate_if_quoted_pct,

    -- Match type breakdown
    countIf(match_type = 'MATCH') AS matches,
    countIf(match_type = 'WOULD_QUOTE_NO_FILL') AS would_quote_no_fill,
    countIf(match_type = 'OUTSIDE_TIME_WINDOW') AS outside_time_window,
    countIf(match_type = 'INSUFFICIENT_EDGE') AS insufficient_edge,

    -- Price accuracy
    round(avgIf(actual_price - our_quote_price, match_type = 'MATCH'), 4) AS avg_price_diff,
    round(medianIf(actual_price - our_quote_price, match_type = 'MATCH'), 4) AS median_price_diff,

    -- Size comparison
    round(avgIf(actual_size, match_type = 'MATCH'), 2) AS avg_gabagool_size,
    round(avgIf(our_quote_size, match_type = 'MATCH'), 2) AS avg_our_size,

    -- PnL
    round(sum(actual_pnl), 2) AS gabagool_total_pnl,
    round(sumIf(simulated_pnl, match_type = 'MATCH'), 2) AS our_simulated_pnl,
    round(sumIf(actual_pnl, match_type = 'MATCH'), 2) AS gabagool_pnl_on_matches,

    -- Edge stats
    round(avg(complete_set_edge) * 100, 3) AS avg_edge_pct,
    round(median(complete_set_edge) * 100, 3) AS median_edge_pct

FROM polybot.strategy_validation_clean;
