-- Research-ready views for Python notebooks.

CREATE OR REPLACE VIEW polybot.user_trade_research AS
WITH
  0.000001 AS eps,
  (tob_captured_at IS NOT NULL AND best_bid_price > 0 AND best_ask_price > 0) AS tob_known,
  (tob_known AND mid > 0) AS mid_known,
  (mid_known AND spread > 0) AS spread_known,
  multiIf(
    position(market_slug, 'updown-15m-') > 0, 'updown-15m',
    position(lower(title), 'up or down') > 0, 'up-or-down',
    position(lower(market_slug), 'updown') > 0, 'updown',
    'other'
  ) AS series,
  if(position(market_slug, 'updown-15m-') > 0, upper(arrayElement(splitByChar('-', market_slug), 1)), '') AS asset,
  multiIf(
    tob_known = 0, 'UNKNOWN',
    side = 'BUY' AND price >= best_ask_price - eps, 'TAKER_LIKE',
    side = 'BUY' AND price <= best_bid_price + eps, 'MAKER_LIKE',
    side = 'SELL' AND price <= best_bid_price + eps, 'TAKER_LIKE',
    side = 'SELL' AND price >= best_ask_price - eps, 'MAKER_LIKE',
    price > best_bid_price + eps AND price < best_ask_price - eps, 'INSIDE',
    'OUTSIDE'
  ) AS exec_type,
  multiIf(
    mid_known = 0, CAST(NULL, 'Nullable(Float64)'),
    side = 'BUY', mid - price,
    side = 'SELL', price - mid,
    CAST(NULL, 'Nullable(Float64)')
  ) AS edge_vs_mid,
  if(mid_known = 0, CAST(NULL, 'Nullable(Float64)'), 2 * abs(price - mid)) AS effective_spread,
  if(spread_known = 0, CAST(NULL, 'Nullable(Float64)'), (2 * abs(price - mid)) / spread) AS effective_spread_ratio,
  if(tob_captured_at IS NULL, CAST(NULL, 'Nullable(Int64)'), dateDiff('millisecond', ts, tob_captured_at)) AS tob_lag_millis,
  if(
    tob_known = 0 OR (best_bid_size + best_ask_size) <= 0,
    CAST(NULL, 'Nullable(Float64)'),
    (best_bid_size - best_ask_size) / (best_bid_size + best_ask_size)
  ) AS tob_imbalance
SELECT
  ts,
  toDate(ts) AS day,
  toHour(ts) AS hour_utc,
  username,
  proxy_address,
  market_slug,
  title,
  token_id,
  condition_id,
  side,
  outcome,
  outcome_index,
  price,
  size,
  price * size AS notional_usd,
  transaction_hash,
  event_key,
  series,
  asset,
  seconds_to_end,
  end_date,
  event_start_time,
  active,
  closed,
  uma_resolution_status,
  is_resolved,
  resolved_outcome,
  settle_price,
  realized_pnl,
  tob_captured_at,
  tob_known,
  tob_lag_millis,
  best_bid_price,
  best_bid_size,
  best_ask_price,
  best_ask_size,
  mid,
  spread,
  price_minus_mid,
  tob_imbalance,
  exec_type,
  edge_vs_mid,
  effective_spread,
  effective_spread_ratio
FROM polybot.user_trade_enriched;

