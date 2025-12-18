-- =============================================================================
-- STRATEGY VALIDATION FRAMEWORK
-- =============================================================================
-- Purpose: Compare our simulated strategy against gabagool22's actual trades
-- to validate that our implementation correctly replicates his behavior.
--
-- Components:
--   1. strategy_replay_input     - Enriched gabagool22 trades with both-sides TOB
--   2. strategy_replay_decision  - What OUR strategy would have done at each trade
--   3. replication_score_summary - Aggregate metrics on how closely we match
-- =============================================================================


-- =============================================================================
-- 1) STRATEGY REPLAY INPUT
-- =============================================================================
-- For each gabagool22 trade, gather all inputs our strategy would need:
--   - Market metadata (slug, series type, seconds_to_end)
--   - Book state for the TRADED token
--   - Book state for the OTHER token (for complete-set edge calculation)

CREATE OR REPLACE VIEW polybot.strategy_replay_input AS
WITH
    -- Parse market series from slug
    market_series AS (
        SELECT
            *,
            multiIf(
                market_slug LIKE 'btc-updown-15m-%', 'btc-15m',
                market_slug LIKE 'eth-updown-15m-%', 'eth-15m',
                market_slug LIKE 'bitcoin-up-or-down-%', 'btc-1h',
                market_slug LIKE 'ethereum-up-or-down-%', 'eth-1h',
                'other'
            ) AS series,
            -- Determine the other token_id from the token_ids array
            if(outcome = 'Up',
               arrayElement(token_ids, 2),  -- Down token
               arrayElement(token_ids, 1)   -- Up token
            ) AS other_token_id
        FROM polybot.user_trade_enriched_v3
        WHERE username = 'gabagool22'
          AND market_slug LIKE '%updown%' OR market_slug LIKE '%up-or-down%'
    ),
    -- Get other side's book state from the WS TOB (ASOF join)
    with_other_tob AS (
        SELECT
            m.*,
            tob.best_bid_price AS other_best_bid,
            tob.best_bid_size AS other_best_bid_size,
            tob.best_ask_price AS other_best_ask,
            tob.best_ask_size AS other_best_ask_size,
            (tob.best_bid_price + tob.best_ask_price) / 2 AS other_mid
        FROM market_series m
        ASOF LEFT JOIN polybot.market_ws_tob tob
            ON m.other_token_id = tob.token_id AND m.ts >= tob.ts
    )
SELECT
    ts,
    market_slug,
    series,
    token_id,
    other_token_id,
    outcome,
    side,
    price AS actual_fill_price,
    size AS actual_fill_size,
    seconds_to_end,

    -- Our side's book (use WS if available, else REST)
    coalesce(ws_best_bid_price, best_bid_price) AS our_best_bid,
    coalesce(ws_best_bid_size, best_bid_size) AS our_best_bid_size,
    coalesce(ws_best_ask_price, best_ask_price) AS our_best_ask,
    coalesce(ws_best_ask_size, best_ask_size) AS our_best_ask_size,
    coalesce(ws_mid, mid) AS our_mid,

    -- Other side's book
    other_best_bid,
    other_best_bid_size,
    other_best_ask,
    other_best_ask_size,
    other_mid,

    -- Complete-set edge: 1 - (bid_up + bid_down)
    -- If we're trading UP, other is DOWN
    if(outcome = 'Up',
       1 - coalesce(ws_best_bid_price, best_bid_price, 0) - coalesce(other_best_bid, 0),
       1 - coalesce(other_best_bid, 0) - coalesce(ws_best_bid_price, best_bid_price, 0)
    ) AS complete_set_edge,

    -- Book imbalance for directional signal
    book_imbalance,

    -- Resolution info
    is_resolved,
    resolved_outcome,
    settle_price,
    realized_pnl,

    -- Data quality flags
    if(coalesce(ws_best_bid_price, best_bid_price) > 0, 1, 0) AS has_our_tob,
    if(other_best_bid > 0, 1, 0) AS has_other_tob,
    ws_tob_lag_millis
FROM with_other_tob
WHERE series != 'other';


-- =============================================================================
-- 2) STRATEGY REPLAY DECISION
-- =============================================================================
-- Apply our strategy logic to each trade and determine:
--   - Would we have quoted this market?
--   - At what price/size?
--   - Would we have gotten filled?

CREATE OR REPLACE VIEW polybot.strategy_replay_decision AS
WITH
    -- Strategy parameters (matching application-develop.yaml)
    params AS (
        SELECT
            0.01 AS min_complete_set_edge,
            0 AS min_seconds_to_end,
            3600 AS max_seconds_to_end,
            0 AS improve_ticks,  -- quote AT best bid
            0.01 AS tick_size
    ),
    -- Base sizing by series (from research)
    base_sizes AS (
        SELECT
            'btc-15m' AS series, 19.0 AS base_shares
        UNION ALL SELECT 'eth-15m', 14.0
        UNION ALL SELECT 'btc-1h', 18.0
        UNION ALL SELECT 'eth-1h', 14.0
    ),
    -- Apply strategy logic
    decisions AS (
        SELECT
            r.*,
            b.base_shares,

            -- Time window check
            r.seconds_to_end >= (SELECT min_seconds_to_end FROM params)
              AND r.seconds_to_end <= (SELECT max_seconds_to_end FROM params) AS in_time_window,

            -- Complete-set edge check (need both sides TOB)
            r.has_our_tob = 1 AND r.has_other_tob = 1
              AND r.complete_set_edge >= (SELECT min_complete_set_edge FROM params) AS has_sufficient_edge,

            -- Our quote price (AT best bid, no improvement)
            r.our_best_bid AS our_quote_price,

            -- Would we quote this market?
            (r.seconds_to_end >= (SELECT min_seconds_to_end FROM params)
              AND r.seconds_to_end <= (SELECT max_seconds_to_end FROM params)
              AND r.has_our_tob = 1 AND r.has_other_tob = 1
              AND r.complete_set_edge >= (SELECT min_complete_set_edge FROM params)
            ) AS would_quote,

            -- Fill probability: did gabagool22 fill AT or BETTER than our quote?
            r.actual_fill_price <= r.our_best_bid + 0.01 AS likely_would_fill,

            -- Price comparison
            r.actual_fill_price - r.our_best_bid AS price_diff_vs_our_quote

        FROM polybot.strategy_replay_input r
        LEFT JOIN base_sizes b ON r.series = b.series
    )
SELECT
    ts,
    market_slug,
    series,
    token_id,
    outcome,
    side,
    seconds_to_end,
    actual_fill_price,
    actual_fill_size,
    our_quote_price,
    base_shares AS our_quote_size,
    complete_set_edge,

    -- Decision flags
    in_time_window,
    has_sufficient_edge,
    would_quote,
    likely_would_fill,
    price_diff_vs_our_quote,

    -- Match classification
    multiIf(
        would_quote AND likely_would_fill, 'MATCH',
        would_quote AND NOT likely_would_fill, 'WOULD_QUOTE_NO_FILL',
        NOT in_time_window, 'OUTSIDE_TIME_WINDOW',
        NOT has_sufficient_edge, 'INSUFFICIENT_EDGE',
        'NO_TOB_DATA'
    ) AS match_type,

    -- PnL if we had matched
    if(would_quote AND likely_would_fill,
       (settle_price - our_quote_price) * base_shares,
       0
    ) AS simulated_pnl,

    realized_pnl AS actual_pnl,

    -- Data quality
    has_our_tob,
    has_other_tob,
    ws_tob_lag_millis
FROM decisions;


-- =============================================================================
-- 3) REPLICATION SCORE SUMMARY
-- =============================================================================
-- Aggregate metrics showing how closely our strategy matches gabagool22

CREATE OR REPLACE VIEW polybot.strategy_replication_score AS
SELECT
    -- Overall metrics
    count() AS total_gabagool_trades,
    countIf(would_quote) AS we_would_quote,
    countIf(would_quote AND likely_would_fill) AS we_would_match,

    -- Match rates
    round(countIf(would_quote) * 100.0 / count(), 2) AS quote_rate_pct,
    round(countIf(would_quote AND likely_would_fill) * 100.0 / count(), 2) AS match_rate_pct,
    round(countIf(would_quote AND likely_would_fill) * 100.0 / nullif(countIf(would_quote), 0), 2) AS fill_rate_if_quoted_pct,

    -- Match type breakdown
    countIf(match_type = 'MATCH') AS matches,
    countIf(match_type = 'WOULD_QUOTE_NO_FILL') AS would_quote_no_fill,
    countIf(match_type = 'OUTSIDE_TIME_WINDOW') AS outside_time_window,
    countIf(match_type = 'INSUFFICIENT_EDGE') AS insufficient_edge,
    countIf(match_type = 'NO_TOB_DATA') AS no_tob_data,

    -- Price accuracy (when we match)
    round(avgIf(price_diff_vs_our_quote, match_type = 'MATCH'), 4) AS avg_price_diff_when_match,
    round(avgIf(abs(price_diff_vs_our_quote), match_type = 'MATCH'), 4) AS avg_abs_price_diff,

    -- Size accuracy
    round(avgIf(actual_fill_size, match_type = 'MATCH'), 2) AS avg_actual_size_when_match,
    round(avgIf(our_quote_size, match_type = 'MATCH'), 2) AS avg_our_size_when_match,

    -- PnL comparison
    round(sum(actual_pnl), 2) AS gabagool_total_pnl,
    round(sumIf(simulated_pnl, match_type = 'MATCH'), 2) AS our_simulated_pnl_on_matches,
    round(sumIf(actual_pnl, match_type = 'MATCH'), 2) AS gabagool_pnl_on_matches,

    -- Data quality metrics
    round(avgIf(complete_set_edge, has_our_tob = 1 AND has_other_tob = 1) * 100, 3) AS avg_complete_set_edge_pct,
    round(countIf(has_our_tob = 1 AND has_other_tob = 1) * 100.0 / count(), 2) AS dual_tob_coverage_pct

FROM polybot.strategy_replay_decision;


-- =============================================================================
-- 4) REPLICATION SCORE BY SERIES
-- =============================================================================
-- Break down replication metrics by market series

CREATE OR REPLACE VIEW polybot.strategy_replication_by_series AS
SELECT
    series,
    count() AS total_trades,
    countIf(match_type = 'MATCH') AS matches,
    round(countIf(match_type = 'MATCH') * 100.0 / count(), 2) AS match_rate_pct,

    round(avgIf(actual_fill_size, match_type = 'MATCH'), 2) AS avg_actual_size,
    round(avgIf(our_quote_size, match_type = 'MATCH'), 2) AS avg_our_size,
    round(avgIf(actual_fill_size, match_type = 'MATCH') / nullif(avgIf(our_quote_size, match_type = 'MATCH'), 0), 3) AS size_ratio,

    round(sum(actual_pnl), 2) AS gabagool_pnl,
    round(sumIf(simulated_pnl, match_type = 'MATCH'), 2) AS our_simulated_pnl,

    round(avgIf(complete_set_edge, has_our_tob = 1 AND has_other_tob = 1) * 100, 3) AS avg_edge_pct

FROM polybot.strategy_replay_decision
GROUP BY series
ORDER BY series;


-- =============================================================================
-- 5) REPLICATION SCORE BY TIME BUCKET
-- =============================================================================
-- See how replication varies by seconds_to_end

CREATE OR REPLACE VIEW polybot.strategy_replication_by_time AS
SELECT
    floor(seconds_to_end / 60) * 60 AS seconds_to_end_bucket,
    count() AS total_trades,
    countIf(match_type = 'MATCH') AS matches,
    round(countIf(match_type = 'MATCH') * 100.0 / count(), 2) AS match_rate_pct,
    round(avgIf(complete_set_edge, has_our_tob = 1 AND has_other_tob = 1) * 100, 3) AS avg_edge_pct

FROM polybot.strategy_replay_decision
WHERE seconds_to_end IS NOT NULL
GROUP BY seconds_to_end_bucket
ORDER BY seconds_to_end_bucket;
