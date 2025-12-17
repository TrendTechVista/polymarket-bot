-- Enhanced data collection schema additions
-- Adds order book depth, resolution tracking, execution details, and flow metrics

-- =============================================================================
-- 1. ORDER BOOK DEPTH: Add full book levels to CLOB TOB
-- =============================================================================

-- Add columns to clob_tob for order book depth (if not already present from previous migration)
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS bid_levels Array(Tuple(price Float64, size Float64));
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS ask_levels Array(Tuple(price Float64, size Float64));
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS total_bid_volume Float64;
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS total_ask_volume Float64;
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS book_imbalance Float64;
ALTER TABLE polybot.clob_tob ADD COLUMN IF NOT EXISTS depth_at_1pct Float64;


-- =============================================================================
-- 2. MARKET RESOLUTIONS: Track when markets resolve and settle
-- =============================================================================

CREATE TABLE IF NOT EXISTS polybot.market_resolutions (
  market_slug LowCardinality(String),
  market_id String,
  resolved_outcome LowCardinality(String),
  outcome_prices Array(Float64),
  resolved_at DateTime64(3),
  detected_at DateTime64(3),
  uma_resolution_status LowCardinality(String),
  resolution_source LowCardinality(String) DEFAULT 'gamma_api',
  event_key String,
  ingested_at DateTime64(3) DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toDate(resolved_at)
ORDER BY (market_slug, resolved_at);


-- =============================================================================
-- 3. EXECUTION DETAILS: Add gas/block info to user trades
-- =============================================================================

ALTER TABLE polybot.user_trades ADD COLUMN IF NOT EXISTS gas_price Float64;
ALTER TABLE polybot.user_trades ADD COLUMN IF NOT EXISTS gas_used Float64;
ALTER TABLE polybot.user_trades ADD COLUMN IF NOT EXISTS block_number UInt64;
ALTER TABLE polybot.user_trades ADD COLUMN IF NOT EXISTS block_timestamp DateTime64(3);
ALTER TABLE polybot.user_trades ADD COLUMN IF NOT EXISTS tx_status LowCardinality(String);


-- =============================================================================
-- 4. ENHANCED CLOB_TOB_BY_TRADE: Include order book depth
-- =============================================================================

CREATE OR REPLACE VIEW polybot.clob_tob_by_trade_v2 AS
SELECT
  trade_key,
  max(captured_at) AS tob_captured_at,
  argMax(market_slug, captured_at) AS market_slug,
  argMax(token_id, captured_at) AS token_id,
  argMax(outcome, captured_at) AS outcome,
  argMax(best_bid_price, captured_at) AS best_bid_price,
  argMax(best_bid_size, captured_at) AS best_bid_size,
  argMax(best_ask_price, captured_at) AS best_ask_price,
  argMax(best_ask_size, captured_at) AS best_ask_size,
  argMax(mid, captured_at) AS mid,
  argMax(spread, captured_at) AS spread,
  argMax(book_timestamp_ms, captured_at) AS book_timestamp_ms,
  argMax(book_hash, captured_at) AS book_hash,
  argMax(cached, captured_at) AS cached,
  argMax(cache_age_millis, captured_at) AS cache_age_millis,
  -- NEW: Order book depth
  argMax(bid_levels, captured_at) AS bid_levels,
  argMax(ask_levels, captured_at) AS ask_levels,
  argMax(total_bid_volume, captured_at) AS total_bid_volume,
  argMax(total_ask_volume, captured_at) AS total_ask_volume,
  argMax(book_imbalance, captured_at) AS book_imbalance,
  argMax(depth_at_1pct, captured_at) AS depth_at_1pct
FROM polybot.clob_tob
GROUP BY trade_key;


-- =============================================================================
-- 5. RESOLUTION LATEST: Get most recent resolution for each market
-- =============================================================================

CREATE OR REPLACE VIEW polybot.market_resolutions_latest AS
SELECT
  market_slug,
  argMax(market_id, resolved_at) AS market_id,
  argMax(resolved_outcome, resolved_at) AS resolved_outcome,
  argMax(outcome_prices, resolved_at) AS outcome_prices,
  max(resolved_at) AS latest_resolved_at,
  argMax(detected_at, resolved_at) AS detected_at,
  argMax(uma_resolution_status, resolved_at) AS uma_resolution_status
FROM polybot.market_resolutions
GROUP BY market_slug;


-- =============================================================================
-- 6. ENHANCED TRADE ENRICHMENT: Include new fields
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_trade_enriched_v2 AS
WITH
  toDateTime64('2000-01-01 00:00:00', 3) AS min_valid_dt,
  if(g.end_date < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(g.end_date)) AS gamma_end_date,
  if(g.event_start_time < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(g.event_start_time)) AS gamma_event_start_time,
  toUInt32OrZero(arrayElement(splitByChar('-', u.market_slug), -1)) AS slug_epoch_start,
  if(
    position(u.market_slug, 'updown-15m-') > 0 AND slug_epoch_start > 0,
    toDateTime64(slug_epoch_start + 900, 3),
    CAST(NULL, 'Nullable(DateTime64(3))')
  ) AS slug_end_date,
  coalesce(gamma_end_date, slug_end_date) AS end_date,
  if(end_date IS NULL, CAST(NULL, 'Nullable(Int64)'), dateDiff('second', u.ts, end_date)) AS seconds_to_end,
  arrayMax(g.outcome_prices) AS max_outcome_price,
  arrayMin(g.outcome_prices) AS min_outcome_price,
  (max_outcome_price >= 0.999 AND min_outcome_price <= 0.001) AS is_resolved,
  indexOf(g.outcomes, u.outcome) AS trade_outcome_idx,
  if(is_resolved AND trade_outcome_idx > 0, arrayElement(g.outcome_prices, trade_outcome_idx), CAST(NULL, 'Nullable(Float64)')) AS settle_price
SELECT
  -- Original fields
  u.ts,
  u.username,
  u.proxy_address,
  u.market_slug,
  u.title,
  u.token_id,
  u.condition_id,
  u.side,
  u.outcome,
  u.outcome_index,
  u.price,
  u.size,
  u.transaction_hash,
  u.event_key,

  -- TOB at trade time
  t.tob_captured_at,
  t.best_bid_price,
  t.best_bid_size,
  t.best_ask_price,
  t.best_ask_size,
  t.mid,
  t.spread,
  (u.price - t.mid) AS price_minus_mid,

  -- NEW: Order book depth
  t.bid_levels,
  t.ask_levels,
  t.total_bid_volume,
  t.total_ask_volume,
  t.book_imbalance,
  t.depth_at_1pct,

  -- Book imbalance calculated from depth
  if(t.total_bid_volume + t.total_ask_volume > 0,
     (t.total_bid_volume - t.total_ask_volume) / (t.total_bid_volume + t.total_ask_volume),
     NULL) AS full_book_imbalance,

  -- TOB imbalance (from best levels only)
  if(t.best_bid_size + t.best_ask_size > 0,
     (t.best_bid_size - t.best_ask_size) / (t.best_bid_size + t.best_ask_size),
     NULL) AS tob_imbalance,

  -- Gamma market metadata
  g.market_id,
  end_date,
  gamma_event_start_time AS event_start_time,
  seconds_to_end,
  g.active,
  g.closed,
  g.uma_resolution_status,
  g.outcomes,
  g.outcome_prices,
  g.token_ids,
  is_resolved,
  if(is_resolved, arrayElement(g.outcomes, indexOf(g.outcome_prices, max_outcome_price)), CAST(NULL, 'Nullable(String)')) AS resolved_outcome,
  settle_price,
  if(
    is_resolved AND settle_price IS NOT NULL,
    u.size * if(u.side = 'SELL', (u.price - settle_price), (settle_price - u.price)),
    CAST(NULL, 'Nullable(Float64)')
  ) AS realized_pnl,

  -- NEW: Resolution timing from resolution table
  r.latest_resolved_at AS market_resolved_at,
  if(r.latest_resolved_at IS NOT NULL, dateDiff('second', u.ts, r.latest_resolved_at), NULL) AS seconds_to_resolution,

  -- Execution classification
  multiIf(
    t.best_ask_price > 0 AND u.side = 'BUY' AND u.price >= t.best_ask_price - 0.001, 'TAKER_LIKE',
    t.best_bid_price > 0 AND u.side = 'BUY' AND u.price <= t.best_bid_price + 0.001, 'MAKER_LIKE',
    t.best_bid_price > 0 AND u.side = 'SELL' AND u.price <= t.best_bid_price + 0.001, 'TAKER_LIKE',
    t.best_ask_price > 0 AND u.side = 'SELL' AND u.price >= t.best_ask_price - 0.001, 'MAKER_LIKE',
    t.mid > 0, 'INSIDE',
    'UNKNOWN'
  ) AS exec_type

FROM polybot.user_trades_dedup u
LEFT JOIN polybot.clob_tob_by_trade_v2 t ON t.trade_key = u.event_key
LEFT JOIN polybot.gamma_markets_latest g ON g.slug = u.market_slug
LEFT JOIN polybot.market_resolutions_latest r ON r.market_slug = u.market_slug;


-- =============================================================================
-- 7. FLOW METRICS: Market activity aggregates
-- =============================================================================

-- Enhanced minute-level aggregates with buy/sell breakdown
CREATE OR REPLACE VIEW polybot.market_trade_activity_enhanced AS
SELECT
  token_id,
  toStartOfMinute(ts) AS minute,
  count() AS trade_count,
  sum(size) AS volume,
  sum(size * price) AS notional,
  if(sum(size) > 0, sum(size * price) / sum(size), NULL) AS vwap,
  min(price) AS low_price,
  max(price) AS high_price,
  max(price) - min(price) AS price_range,
  stddevPop(price) AS price_volatility,

  -- Buy/Sell breakdown
  sumIf(size, side = 'BUY') AS buy_volume,
  sumIf(size, side = 'SELL') AS sell_volume,
  countIf(side = 'BUY') AS buy_count,
  countIf(side = 'SELL') AS sell_count,

  -- Flow imbalance: positive = more buying, negative = more selling
  if(sum(size) > 0,
     (sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL')) / sum(size),
     0) AS flow_imbalance,

  min(ts) AS first_trade_at,
  max(ts) AS last_trade_at
FROM polybot.market_trades
GROUP BY token_id, minute;


-- Trade with flow metrics
CREATE OR REPLACE VIEW polybot.user_trade_with_flow AS
SELECT
  u.*,

  -- Volume context (1 min before)
  ma_before.volume AS volume_1m_before,
  ma_before.trade_count AS trades_1m_before,
  ma_before.flow_imbalance AS flow_imbalance_1m_before,
  ma_before.price_volatility AS volatility_1m_before,
  ma_before.vwap AS vwap_1m_before,

  -- Volume context (1 min after)
  ma_after.volume AS volume_1m_after,
  ma_after.trade_count AS trades_1m_after,
  ma_after.flow_imbalance AS flow_imbalance_1m_after,

  -- Volume acceleration (change in volume rate)
  if(ma_before.volume > 0 AND ma_2min.volume > 0,
     (ma_before.volume - ma_2min.volume) / ma_2min.volume,
     NULL) AS volume_acceleration,

  -- Price vs VWAP
  if(ma_before.vwap > 0, u.price - ma_before.vwap, NULL) AS price_vs_vwap

FROM polybot.user_trades_dedup u
LEFT JOIN polybot.market_trade_activity_enhanced ma_before
  ON ma_before.token_id = u.token_id
  AND ma_before.minute = toStartOfMinute(u.ts) - INTERVAL 1 MINUTE
LEFT JOIN polybot.market_trade_activity_enhanced ma_after
  ON ma_after.token_id = u.token_id
  AND ma_after.minute = toStartOfMinute(u.ts) + INTERVAL 1 MINUTE
LEFT JOIN polybot.market_trade_activity_enhanced ma_2min
  ON ma_2min.token_id = u.token_id
  AND ma_2min.minute = toStartOfMinute(u.ts) - INTERVAL 2 MINUTE;


-- =============================================================================
-- 8. RESEARCH VIEW: Combined enriched trade with all features
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_trade_research_v2 AS
SELECT
  e.*,

  -- Flow metrics from separate view
  f.volume_1m_before,
  f.trades_1m_before,
  f.flow_imbalance_1m_before,
  f.volatility_1m_before,
  f.vwap_1m_before,
  f.volume_1m_after,
  f.trades_1m_after,
  f.flow_imbalance_1m_after,
  f.volume_acceleration,
  f.price_vs_vwap

FROM polybot.user_trade_enriched_v2 e
LEFT JOIN polybot.user_trade_with_flow f
  ON f.event_key = e.event_key;
