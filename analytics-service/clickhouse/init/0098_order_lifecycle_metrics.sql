-- =============================================================================
-- ORDER LIFECYCLE METRICS (strategy + executor)
-- =============================================================================
-- Purpose:
--   Provide queryable distributions for:
--   - cadence (place/replace/cancel per time)
--   - cancel/replace age distributions (order lifecycle)
--   - maker fill-time / queue proxy distributions (via executor polling snapshots)
--
-- These views are designed to support:
--   1) "decision stream replication score" against inferred gabagool behavior
--   2) calibration of the paper fill/queue model (PaperExchangeSimulator)
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1) Per-order lifecycle (join strategy events + executor status)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_lifecycle_by_order AS
WITH
  toDateTime64(0, 3) AS epoch,
  placed AS (
    SELECT
      order_id,
      anyLast(run_id) AS run_id,
      anyLast(strategy) AS strategy,
      anyLast(market_slug) AS market_slug,
      anyLast(market_type) AS market_type,
      anyLast(token_id) AS token_id,
      anyLast(direction) AS direction,
      nullIf(minIf(ts, action = 'PLACE' AND success = 1), epoch) AS placed_ts,
      argMinIf(price, ts, action = 'PLACE' AND success = 1) AS placed_price,
      argMinIf(size, ts, action = 'PLACE' AND success = 1) AS placed_size,
      argMinIf(tick_size, ts, action = 'PLACE' AND success = 1) AS tick_size,
      argMinIf(best_bid_price, ts, action = 'PLACE' AND success = 1) AS best_bid_price_at_place,
      argMinIf(best_ask_price, ts, action = 'PLACE' AND success = 1) AS best_ask_price_at_place,
      nullIf(minIf(ts, action = 'CANCEL' AND success = 1), epoch) AS cancel_ts,
      countIf(action = 'REPLACE' AND success = 1) AS replace_count,
      min(ts) AS first_strategy_ts,
      max(ts) AS last_strategy_ts
    FROM polybot.strategy_gabagool_orders
    WHERE order_id != ''
    GROUP BY order_id
  ),
  exec AS (
    SELECT
      order_id,
      max(ifNull(matched_size, 0)) AS filled_size,
      if(max(ifNull(matched_size, 0)) > 0, nullIf(minIf(ts, matched_size > 0), epoch), CAST(NULL, 'Nullable(DateTime64(3))')) AS first_fill_ts,
      argMax(exchange_status, ts) AS last_exchange_status,
      argMax(requested_price, ts) AS last_requested_price,
      argMax(requested_size, ts) AS last_requested_size
    FROM polybot.executor_order_status
    WHERE order_id != ''
    GROUP BY order_id
  )
SELECT
  p.order_id,
  p.run_id,
  p.strategy,
  p.market_slug,
  p.market_type,
  p.token_id,
  p.direction,
  p.placed_ts,
  p.placed_price,
  p.placed_size,
  p.tick_size,
  p.best_bid_price_at_place,
  p.best_ask_price_at_place,
  e.first_fill_ts,
  e.filled_size,
  e.last_exchange_status,
  e.last_requested_price,
  e.last_requested_size,
  p.cancel_ts,
  p.replace_count,
  p.first_strategy_ts,
  p.last_strategy_ts,
  if(p.placed_ts IS NULL OR e.first_fill_ts IS NULL, CAST(NULL, 'Nullable(Int64)'),
     dateDiff('millisecond', p.placed_ts, e.first_fill_ts)) AS fill_latency_ms,
  if(p.placed_ts IS NULL OR p.cancel_ts IS NULL, CAST(NULL, 'Nullable(Int64)'),
     dateDiff('millisecond', p.placed_ts, p.cancel_ts)) AS cancel_latency_ms,
  -- Queue/priority proxy at placement: how many ticks above best bid was our BUY?
  if(p.tick_size IS NULL OR p.tick_size = 0 OR p.placed_price IS NULL OR p.best_bid_price_at_place IS NULL,
     CAST(NULL, 'Nullable(Int64)'),
     toInt64(floor((p.placed_price - p.best_bid_price_at_place) / p.tick_size))) AS ticks_above_best_bid_at_place
FROM placed p
LEFT JOIN exec e USING (order_id);


-- -----------------------------------------------------------------------------
-- 2) Lifecycle summary (high-level distribution inputs)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_lifecycle_summary AS
SELECT
  count() AS orders_seen,
  countIf(placed_ts IS NOT NULL) AS orders_placed,
  countIf(first_fill_ts IS NOT NULL) AS orders_with_fill,
  countIf(cancel_ts IS NOT NULL) AS orders_canceled,
  countIf(first_fill_ts IS NULL AND cancel_ts IS NULL) AS orders_unresolved,
  round(avgIf(fill_latency_ms, fill_latency_ms IS NOT NULL) / 1000, 3) AS avg_fill_latency_s,
  round(medianIf(fill_latency_ms, fill_latency_ms IS NOT NULL) / 1000, 3) AS median_fill_latency_s,
  round(quantileIf(0.9)(fill_latency_ms, fill_latency_ms IS NOT NULL) / 1000, 3) AS p90_fill_latency_s,
  round(avg(replace_count), 3) AS avg_replaces_per_order,
  round(sum(replace_count) / nullIf(countIf(placed_ts IS NOT NULL), 0), 3) AS replaces_per_placed_order
FROM polybot.order_lifecycle_by_order;


-- -----------------------------------------------------------------------------
-- 3) Fill latency histogram (for simulator calibration)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_fill_latency_hist AS
SELECT
  -- bucket in seconds
  floor(fill_latency_ms / 1000) AS fill_latency_s_bucket,
  count() AS orders
FROM polybot.order_lifecycle_by_order
WHERE fill_latency_ms IS NOT NULL
GROUP BY fill_latency_s_bucket
ORDER BY fill_latency_s_bucket;

-- -----------------------------------------------------------------------------
-- 3b) Cancel latency histogram (for cancel/replace cadence calibration)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_cancel_latency_hist AS
SELECT
  floor(cancel_latency_ms / 1000) AS cancel_latency_s_bucket,
  count() AS orders
FROM polybot.order_lifecycle_by_order
WHERE cancel_latency_ms IS NOT NULL
GROUP BY cancel_latency_s_bucket
ORDER BY cancel_latency_s_bucket;

-- -----------------------------------------------------------------------------
-- 3c) Replace-count distribution
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_replace_count_hist AS
SELECT
  replace_count,
  count() AS orders
FROM polybot.order_lifecycle_by_order
WHERE placed_ts IS NOT NULL
GROUP BY replace_count
ORDER BY replace_count;


-- -----------------------------------------------------------------------------
-- 4) Tick-offset vs fill (queue priority proxy)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW polybot.order_fill_by_tick_offset AS
SELECT
  ticks_above_best_bid_at_place,
  count() AS orders,
  countIf(first_fill_ts IS NOT NULL) AS filled_orders,
  round(countIf(first_fill_ts IS NOT NULL) * 100.0 / count(), 2) AS fill_rate_pct,
  round(medianIf(fill_latency_ms, first_fill_ts IS NOT NULL) / 1000, 3) AS median_fill_latency_s
FROM polybot.order_lifecycle_by_order
WHERE placed_ts IS NOT NULL
  AND ticks_above_best_bid_at_place IS NOT NULL
GROUP BY ticks_above_best_bid_at_place
ORDER BY ticks_above_best_bid_at_place;
