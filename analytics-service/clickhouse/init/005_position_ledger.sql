-- Position ledger and complete-set detection views.
-- Built on top of canonical extracted tables.

-- =============================================================================
-- POSITION LEDGER: Running inventory per (username, market_slug, token_id)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_position_ledger AS
WITH
  if(side = 'BUY', size, -size) AS signed_shares,
  if(side = 'BUY', price * size, -price * size) AS signed_cost_usd
SELECT
  ts,
  username,
  market_slug,
  title,
  token_id,
  outcome,
  side,
  price,
  size,
  signed_shares,
  signed_cost_usd,
  sum(signed_shares) OVER w AS position_shares,
  sum(signed_cost_usd) OVER w AS position_cost_usd,
  if(
    sum(signed_shares) OVER w > 0,
    sum(signed_cost_usd) OVER w / sum(signed_shares) OVER w,
    NULL
  ) AS avg_entry_price,
  event_key,
  transaction_hash
FROM polybot.user_trades_dedup
WINDOW w AS (
  PARTITION BY username, market_slug, token_id
  ORDER BY ts
  ROWS UNBOUNDED PRECEDING
)
ORDER BY username, market_slug, token_id, ts;


-- =============================================================================
-- POSITION SUMMARY: Final position state per (username, market_slug, token_id)
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_position_final AS
SELECT
  username,
  market_slug,
  argMax(title, ts) AS title,
  token_id,
  argMax(outcome, ts) AS outcome,
  count() AS trades,
  min(ts) AS first_trade_at,
  max(ts) AS last_trade_at,
  sumIf(size, side = 'BUY') AS buy_shares,
  sumIf(size, side = 'SELL') AS sell_shares,
  sumIf(size, side = 'BUY') - sumIf(size, side = 'SELL') AS net_shares,
  sumIf(price * size, side = 'BUY') AS buy_cost_usd,
  sumIf(price * size, side = 'SELL') AS sell_proceeds_usd,
  sumIf(price * size, side = 'BUY') - sumIf(price * size, side = 'SELL') AS net_cost_usd,
  if(
    sumIf(size, side = 'BUY') > 0,
    sumIf(price * size, side = 'BUY') / sumIf(size, side = 'BUY'),
    NULL
  ) AS avg_buy_price,
  if(
    sumIf(size, side = 'SELL') > 0,
    sumIf(price * size, side = 'SELL') / sumIf(size, side = 'SELL'),
    NULL
  ) AS avg_sell_price
FROM polybot.user_trades_dedup
GROUP BY username, market_slug, token_id;


-- =============================================================================
-- COMPLETE-SET DETECTION: Aggregate-based approach (ClickHouse-friendly)
-- =============================================================================

-- This approach aggregates by market and computes complete-set stats from
-- the overlap between Up/Down (or Yes/No) buys, rather than trying to pair
-- individual trades (which requires complex self-joins not supported by ClickHouse).
--
-- For individual pair detection, use the Python notebook instead.

CREATE OR REPLACE VIEW polybot.user_complete_sets_by_market AS
SELECT
  username,
  market_slug,
  any(title) AS title,

  -- Up/Yes side
  sumIf(size, outcome IN ('Up', 'Yes')) AS up_shares,
  sumIf(size * price, outcome IN ('Up', 'Yes')) AS up_cost,
  countIf(outcome IN ('Up', 'Yes')) AS up_trades,

  -- Down/No side
  sumIf(size, outcome IN ('Down', 'No')) AS down_shares,
  sumIf(size * price, outcome IN ('Down', 'No')) AS down_cost,
  countIf(outcome IN ('Down', 'No')) AS down_trades,

  -- Complete set calculations
  least(up_shares, down_shares) AS matched_shares,
  if(up_shares > 0, up_cost / up_shares, 0) AS avg_up_price,
  if(down_shares > 0, down_cost / down_shares, 0) AS avg_down_price,

  -- Edge: 1 - (avg_up_price + avg_down_price)
  -- Positive edge = guaranteed profit at resolution
  1 - (if(up_shares > 0, up_cost / up_shares, 0) + if(down_shares > 0, down_cost / down_shares, 0)) AS edge_per_share,

  -- Total edge PnL on matched shares
  least(up_shares, down_shares) * (1 - (if(up_shares > 0, up_cost / up_shares, 0) + if(down_shares > 0, down_cost / down_shares, 0))) AS edge_pnl,

  min(ts) AS first_trade_at,
  max(ts) AS last_trade_at

FROM polybot.user_trades_dedup
WHERE side = 'BUY'
  AND outcome IN ('Up', 'Down', 'Yes', 'No')
GROUP BY username, market_slug
HAVING up_shares > 0 AND down_shares > 0;  -- Must have both sides


-- =============================================================================
-- COMPLETE-SET SUMMARY: Aggregate stats per username
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_complete_sets_summary AS
SELECT
  username,
  count() AS total_markets,
  countIf(edge_per_share > 0) AS positive_edge_markets,
  sum(matched_shares) AS total_matched_shares,
  sum(edge_pnl) AS total_edge_pnl,
  if(sum(matched_shares) > 0, sum(edge_pnl) / sum(matched_shares), 0) AS avg_edge_per_share,
  avg(up_trades + down_trades) AS avg_trades_per_market
FROM polybot.user_complete_sets_by_market
GROUP BY username;


