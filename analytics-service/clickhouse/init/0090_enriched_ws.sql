-- Enrichment add-on: ASOF join trades to continuous market WS TOB snapshots.
--
-- This does NOT replace the existing (trade-triggered) TOB join; it adds a second, much fresher
-- set of book fields that can be used for execution classification and reverse-engineering.

CREATE OR REPLACE VIEW polybot.user_trade_enriched_v3 AS
WITH
  toDateTime64('2000-01-01 00:00:00', 3) AS min_valid_dt,
  if(w.ts < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(w.ts)) AS ws_ts,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w.best_bid_price, 0)) AS ws_best_bid_price,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w.best_bid_size, 0)) AS ws_best_bid_size,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w.best_ask_price, 0)) AS ws_best_ask_price,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w.best_ask_size, 0)) AS ws_best_ask_size,
  if(ws_best_bid_price > 0 AND ws_best_ask_price > 0, (ws_best_bid_price + ws_best_ask_price) / 2, CAST(NULL, 'Nullable(Float64)')) AS ws_mid,
  if(ws_best_bid_price > 0 AND ws_best_ask_price > 0, (ws_best_ask_price - ws_best_bid_price), CAST(NULL, 'Nullable(Float64)')) AS ws_spread,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w.last_trade_price, 0)) AS ws_last_trade_price
SELECT
  e.*,

  -- WS TOB (ASOF to trade timestamp)
  ws_ts AS ws_tob_captured_at,
  if(ws_ts IS NULL, CAST(NULL, 'Nullable(Int64)'), dateDiff('millisecond', ws_ts, e.ts)) AS ws_tob_lag_millis,
  ws_best_bid_price,
  ws_best_bid_size,
  ws_best_ask_price,
  ws_best_ask_size,
  ws_mid,
  ws_spread,
  ws_last_trade_price,

  multiIf(
    ws_best_ask_price > 0 AND e.side = 'BUY' AND e.price >= ws_best_ask_price - 0.001, 'TAKER_LIKE',
    ws_best_bid_price > 0 AND e.side = 'BUY' AND e.price <= ws_best_bid_price + 0.001, 'MAKER_LIKE',
    ws_best_bid_price > 0 AND e.side = 'SELL' AND e.price <= ws_best_bid_price + 0.001, 'TAKER_LIKE',
    ws_best_ask_price > 0 AND e.side = 'SELL' AND e.price >= ws_best_ask_price - 0.001, 'MAKER_LIKE',
    ws_mid > 0, 'INSIDE',
    'UNKNOWN'
  ) AS ws_exec_type

FROM polybot.user_trade_enriched_v2 e
ASOF LEFT JOIN polybot.market_ws_tob w
  ON w.asset_id = e.token_id
  AND e.ts >= w.ts;


-- Paired WS TOB: include the opposite outcome's TOB at the same decision time.
CREATE OR REPLACE VIEW polybot.user_trade_enriched_v4 AS
WITH
  toDateTime64('2000-01-01 00:00:00', 3) AS min_valid_dt,
  if(w2.ts < min_valid_dt, CAST(NULL, 'Nullable(DateTime64(3))'), toNullable(w2.ts)) AS ws_opp_ts,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w2.best_bid_price, 0)) AS ws_opp_best_bid_price,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w2.best_bid_size, 0)) AS ws_opp_best_bid_size,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w2.best_ask_price, 0)) AS ws_opp_best_ask_price,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w2.best_ask_size, 0)) AS ws_opp_best_ask_size,
  if(ws_opp_best_bid_price > 0 AND ws_opp_best_ask_price > 0, (ws_opp_best_bid_price + ws_opp_best_ask_price) / 2, CAST(NULL, 'Nullable(Float64)')) AS ws_opp_mid,
  if(ws_opp_best_bid_price > 0 AND ws_opp_best_ask_price > 0, (ws_opp_best_ask_price - ws_opp_best_bid_price), CAST(NULL, 'Nullable(Float64)')) AS ws_opp_spread,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Float64)'), nullIf(w2.last_trade_price, 0)) AS ws_opp_last_trade_price
SELECT
  e.*,
  nullIf(
    if(
      length(e.token_ids) = 2 AND indexOf(e.outcomes, e.outcome) IN (1, 2),
      arrayElement(e.token_ids, 3 - indexOf(e.outcomes, e.outcome)),
      ''
    ),
    ''
  ) AS opposite_token_id,

  -- WS TOB for the opposite outcome at the same timestamp
  ws_opp_ts AS ws_opp_tob_captured_at,
  if(ws_opp_ts IS NULL, CAST(NULL, 'Nullable(Int64)'), dateDiff('millisecond', ws_opp_ts, e.ts)) AS ws_opp_tob_lag_millis,
  ws_opp_best_bid_price,
  ws_opp_best_bid_size,
  ws_opp_best_ask_price,
  ws_opp_best_ask_size,
  ws_opp_mid,
  ws_opp_spread,
  ws_opp_last_trade_price,
  if(e.ws_best_ask_price > 0 AND ws_opp_best_ask_price > 0, e.ws_best_ask_price + ws_opp_best_ask_price, CAST(NULL, 'Nullable(Float64)')) AS ws_complete_set_ask_sum,
  if(ws_complete_set_ask_sum IS NULL, CAST(NULL, 'Nullable(Float64)'), 1 - ws_complete_set_ask_sum) AS ws_complete_set_edge_ask

FROM polybot.user_trade_enriched_v3 e
ASOF LEFT JOIN polybot.market_ws_tob w2
  ON w2.asset_id = nullIf(
    if(
      length(e.token_ids) = 2 AND indexOf(e.outcomes, e.outcome) IN (1, 2),
      arrayElement(e.token_ids, 3 - indexOf(e.outcomes, e.outcome)),
      ''
    ),
    ''
  )
  AND e.ts >= w2.ts;
