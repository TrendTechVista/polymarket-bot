-- Microstructure features: market activity context at trade time.
-- These features help understand liquidity conditions when the user trades.

-- =============================================================================
-- MARKET TRADE ACTIVITY: Pre-computed aggregates for efficient joins
-- =============================================================================

-- Note: For large datasets, consider materializing this as a table with
-- incremental refresh instead of computing on-the-fly.

CREATE OR REPLACE VIEW polybot.market_trade_activity_1m AS
SELECT
  token_id,
  toStartOfMinute(ts) AS minute,
  count() AS trade_count,
  sum(size) AS volume,
  sum(size * price) AS notional,
  avg(price) AS avg_price,
  if(sum(size) > 0, sum(size * price) / sum(size), NULL) AS vwap,
  min(price) AS low_price,
  max(price) AS high_price,
  max(price) - min(price) AS price_range,
  min(ts) AS first_trade_at,
  max(ts) AS last_trade_at
FROM polybot.market_trades
GROUP BY token_id, minute;


-- =============================================================================
-- LAST MARKET TRADE: Most recent trade per token (for time-since-last)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.market_trades_last AS
SELECT
  token_id,
  ts,
  price,
  size
FROM polybot.market_trades;


-- =============================================================================
-- USER TRADE WITH MICROSTRUCTURE: Using ASOF JOIN for efficiency
-- =============================================================================

-- Simplified version that uses minute-level aggregates
-- This avoids expensive correlated subqueries
CREATE OR REPLACE VIEW polybot.user_trade_with_microstructure AS
SELECT
  u.*,

  -- Market activity in the minute before the user trade (approximate)
  ma_before.volume AS market_volume_1m_before,
  ma_before.trade_count AS market_trade_count_1m_before,
  ma_before.vwap AS vwap_1m_before,
  ma_before.price_range AS price_range_1m_before,

  -- Market activity in the minute after the user trade
  ma_after.volume AS market_volume_1m_after,
  ma_after.trade_count AS market_trade_count_1m_after

FROM polybot.user_trade_research u
LEFT JOIN polybot.market_trade_activity_1m ma_before
  ON ma_before.token_id = u.token_id
  AND ma_before.minute = toStartOfMinute(u.ts) - INTERVAL 1 MINUTE
LEFT JOIN polybot.market_trade_activity_1m ma_after
  ON ma_after.token_id = u.token_id
  AND ma_after.minute = toStartOfMinute(u.ts) + INTERVAL 1 MINUTE;


-- =============================================================================
-- MICROSTRUCTURE SUMMARY: Aggregate stats per username
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_microstructure_summary AS
SELECT
  username,
  count() AS trades,

  -- Volume context
  avg(market_volume_1m_before) AS avg_volume_1m_before,
  avg(market_trade_count_1m_before) AS avg_trade_count_1m_before,
  avg(market_volume_1m_after) AS avg_volume_1m_after,

  -- VWAP comparison
  avg(vwap_1m_before) AS avg_vwap_1m_before,
  avg(price) AS avg_trade_price,
  avg(price - vwap_1m_before) AS avg_price_vs_vwap,

  -- Volatility context
  avg(price_range_1m_before) AS avg_price_range_1m_before,

  -- Coverage (how often we have market trade data)
  countIf(market_volume_1m_before IS NOT NULL AND market_volume_1m_before > 0) AS trades_with_prior_activity,
  if(count() > 0, countIf(market_volume_1m_before IS NOT NULL AND market_volume_1m_before > 0) / count(), 0) AS prior_activity_coverage

FROM polybot.user_trade_with_microstructure
GROUP BY username;


-- =============================================================================
-- MICROSTRUCTURE BY ASSET: Breakdown by asset for updown-15m markets
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_microstructure_by_asset AS
SELECT
  username,
  asset,
  count() AS trades,
  avg(market_volume_1m_before) AS avg_volume_1m_before,
  avg(market_trade_count_1m_before) AS avg_trade_count_1m_before,
  avg(price_range_1m_before) AS avg_price_range_1m_before,
  avg(price - vwap_1m_before) AS avg_price_vs_vwap
FROM polybot.user_trade_with_microstructure
WHERE asset != ''
GROUP BY username, asset
ORDER BY trades DESC;

