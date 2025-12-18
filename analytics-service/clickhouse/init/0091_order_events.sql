-- Strategy + executor order lifecycle events.
--
-- Goal: make our own bot's order stream queryable (quotes/replaces/cancels/unfilled proxies)
-- so we can calibrate cadence/queue-priority proxies against observed fills.

-- =============================================================================
-- 1) Strategy: gabagool order lifecycle
-- =============================================================================

CREATE TABLE IF NOT EXISTS polybot.strategy_gabagool_orders (
  ts DateTime64(3),
  strategy LowCardinality(String),
  run_id String,
  action LowCardinality(String),
  reason LowCardinality(String),
  market_slug LowCardinality(String),
  market_type LowCardinality(String),
  token_id String,
  direction LowCardinality(String),
  seconds_to_end Nullable(Int64),
  tick_size Nullable(Float64),
  success UInt8,
  error String,
  order_id String,
  price Nullable(Float64),
  size Nullable(Float64),
  replaced_order_id String,
  replaced_price Nullable(Float64),
  replaced_size Nullable(Float64),
  replaced_order_age_millis Nullable(Int64),
  order_age_millis Nullable(Int64),
  best_bid_price Nullable(Float64),
  best_bid_size Nullable(Float64),
  best_ask_price Nullable(Float64),
  best_ask_size Nullable(Float64),
  last_trade_price Nullable(Float64),
  tob_updated_at Nullable(DateTime64(3)),
  last_trade_at Nullable(DateTime64(3)),
  other_token_id String,
  other_best_bid_price Nullable(Float64),
  other_best_bid_size Nullable(Float64),
  other_best_ask_price Nullable(Float64),
  other_best_ask_size Nullable(Float64),
  other_last_trade_price Nullable(Float64),
  other_tob_updated_at Nullable(DateTime64(3)),
  other_last_trade_at Nullable(DateTime64(3)),
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (market_slug, token_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.strategy_gabagool_orders_mv
TO polybot.strategy_gabagool_orders
AS
SELECT
  ts,
  JSONExtractString(data, 'strategy') AS strategy,
  JSONExtractString(data, 'runId') AS run_id,
  JSONExtractString(data, 'action') AS action,
  JSONExtractString(data, 'reason') AS reason,
  JSONExtractString(data, 'marketSlug') AS market_slug,
  JSONExtractString(data, 'marketType') AS market_type,
  JSONExtractString(data, 'tokenId') AS token_id,
  JSONExtractString(data, 'direction') AS direction,
  ifNull(JSONExtractInt(data, 'secondsToEnd'), CAST(NULL, 'Nullable(Int64)')) AS seconds_to_end,
  nullIf(JSONExtractFloat(data, 'tickSize'), 0) AS tick_size,
  ifNull(toUInt8(JSONExtractBool(data, 'success')), 0) AS success,
  JSONExtractString(data, 'error') AS error,
  JSONExtractString(data, 'orderId') AS order_id,
  nullIf(JSONExtractFloat(data, 'price'), 0) AS price,
  nullIf(JSONExtractFloat(data, 'size'), 0) AS size,
  JSONExtractString(data, 'replacedOrderId') AS replaced_order_id,
  nullIf(JSONExtractFloat(data, 'replacedPrice'), 0) AS replaced_price,
  nullIf(JSONExtractFloat(data, 'replacedSize'), 0) AS replaced_size,
  ifNull(JSONExtractInt(data, 'replacedOrderAgeMillis'), CAST(NULL, 'Nullable(Int64)')) AS replaced_order_age_millis,
  ifNull(JSONExtractInt(data, 'orderAgeMillis'), CAST(NULL, 'Nullable(Int64)')) AS order_age_millis,

  nullIf(JSONExtractFloat(data, 'book', 'bestBid'), 0) AS best_bid_price,
  nullIf(JSONExtractFloat(data, 'book', 'bestBidSize'), 0) AS best_bid_size,
  nullIf(JSONExtractFloat(data, 'book', 'bestAsk'), 0) AS best_ask_price,
  nullIf(JSONExtractFloat(data, 'book', 'bestAskSize'), 0) AS best_ask_size,
  nullIf(JSONExtractFloat(data, 'book', 'lastTradePrice'), 0) AS last_trade_price,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'book', 'updatedAt')) AS tob_updated_at,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'book', 'lastTradeAt')) AS last_trade_at,

  JSONExtractString(data, 'otherTokenId') AS other_token_id,
  nullIf(JSONExtractFloat(data, 'otherBook', 'bestBid'), 0) AS other_best_bid_price,
  nullIf(JSONExtractFloat(data, 'otherBook', 'bestBidSize'), 0) AS other_best_bid_size,
  nullIf(JSONExtractFloat(data, 'otherBook', 'bestAsk'), 0) AS other_best_ask_price,
  nullIf(JSONExtractFloat(data, 'otherBook', 'bestAskSize'), 0) AS other_best_ask_size,
  nullIf(JSONExtractFloat(data, 'otherBook', 'lastTradePrice'), 0) AS other_last_trade_price,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'otherBook', 'updatedAt')) AS other_tob_updated_at,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'otherBook', 'lastTradeAt')) AS other_last_trade_at,

  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'strategy.gabagool.order';


-- =============================================================================
-- 2) Executor: order submissions / cancels
-- =============================================================================

CREATE TABLE IF NOT EXISTS polybot.executor_order_limit (
  ts DateTime64(3),
  token_id String,
  side LowCardinality(String),
  price Nullable(Float64),
  size Nullable(Float64),
  order_type LowCardinality(String),
  tick_size Nullable(Float64),
  neg_risk Nullable(UInt8),
  fee_rate_bps Nullable(Int32),
  nonce Nullable(Int64),
  expiration_seconds Nullable(Int64),
  defer_exec Nullable(UInt8),
  mode LowCardinality(String),
  success UInt8,
  order_id String,
  error_type String,
  error_upstream_status Nullable(Int32),
  error_upstream_method String,
  error_upstream_url String,
  error_message String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (token_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.executor_order_limit_mv
TO polybot.executor_order_limit
AS
SELECT
  ts,
  JSONExtractString(data, 'tokenId') AS token_id,
  JSONExtractString(data, 'side') AS side,
  nullIf(JSONExtractFloat(data, 'price'), 0) AS price,
  nullIf(JSONExtractFloat(data, 'size'), 0) AS size,
  JSONExtractString(data, 'orderType') AS order_type,
  nullIf(JSONExtractFloat(data, 'tickSize'), 0) AS tick_size,
  if(JSONHas(data, 'negRisk'), toUInt8(JSONExtractBool(data, 'negRisk')), CAST(NULL, 'Nullable(UInt8)')) AS neg_risk,
  ifNull(JSONExtractInt(data, 'feeRateBps'), CAST(NULL, 'Nullable(Int32)')) AS fee_rate_bps,
  ifNull(JSONExtractInt(data, 'nonce'), CAST(NULL, 'Nullable(Int64)')) AS nonce,
  ifNull(JSONExtractInt(data, 'expirationSeconds'), CAST(NULL, 'Nullable(Int64)')) AS expiration_seconds,
  if(JSONHas(data, 'deferExec'), toUInt8(JSONExtractBool(data, 'deferExec')), CAST(NULL, 'Nullable(UInt8)')) AS defer_exec,
  JSONExtractString(data, 'mode') AS mode,
  ifNull(toUInt8(JSONExtractBool(data, 'success')), 0) AS success,
  JSONExtractString(data, 'orderId') AS order_id,
  JSONExtractString(data, 'error', 'type') AS error_type,
  ifNull(JSONExtractInt(data, 'error', 'upstreamStatus'), CAST(NULL, 'Nullable(Int32)')) AS error_upstream_status,
  JSONExtractString(data, 'error', 'upstreamMethod') AS error_upstream_method,
  JSONExtractString(data, 'error', 'upstreamUrl') AS error_upstream_url,
  JSONExtractString(data, 'error', 'message') AS error_message,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'executor.order.limit';


CREATE TABLE IF NOT EXISTS polybot.executor_order_market (
  ts DateTime64(3),
  token_id String,
  side LowCardinality(String),
  amount Nullable(Float64),
  price Nullable(Float64),
  order_type LowCardinality(String),
  tick_size Nullable(Float64),
  neg_risk Nullable(UInt8),
  fee_rate_bps Nullable(Int32),
  nonce Nullable(Int64),
  defer_exec Nullable(UInt8),
  mode LowCardinality(String),
  success UInt8,
  order_id String,
  error_type String,
  error_upstream_status Nullable(Int32),
  error_upstream_method String,
  error_upstream_url String,
  error_message String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (token_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.executor_order_market_mv
TO polybot.executor_order_market
AS
SELECT
  ts,
  JSONExtractString(data, 'tokenId') AS token_id,
  JSONExtractString(data, 'side') AS side,
  nullIf(JSONExtractFloat(data, 'amount'), 0) AS amount,
  nullIf(JSONExtractFloat(data, 'price'), 0) AS price,
  JSONExtractString(data, 'orderType') AS order_type,
  nullIf(JSONExtractFloat(data, 'tickSize'), 0) AS tick_size,
  if(JSONHas(data, 'negRisk'), toUInt8(JSONExtractBool(data, 'negRisk')), CAST(NULL, 'Nullable(UInt8)')) AS neg_risk,
  ifNull(JSONExtractInt(data, 'feeRateBps'), CAST(NULL, 'Nullable(Int32)')) AS fee_rate_bps,
  ifNull(JSONExtractInt(data, 'nonce'), CAST(NULL, 'Nullable(Int64)')) AS nonce,
  if(JSONHas(data, 'deferExec'), toUInt8(JSONExtractBool(data, 'deferExec')), CAST(NULL, 'Nullable(UInt8)')) AS defer_exec,
  JSONExtractString(data, 'mode') AS mode,
  ifNull(toUInt8(JSONExtractBool(data, 'success')), 0) AS success,
  JSONExtractString(data, 'orderId') AS order_id,
  JSONExtractString(data, 'error', 'type') AS error_type,
  ifNull(JSONExtractInt(data, 'error', 'upstreamStatus'), CAST(NULL, 'Nullable(Int32)')) AS error_upstream_status,
  JSONExtractString(data, 'error', 'upstreamMethod') AS error_upstream_method,
  JSONExtractString(data, 'error', 'upstreamUrl') AS error_upstream_url,
  JSONExtractString(data, 'error', 'message') AS error_message,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'executor.order.market';


CREATE TABLE IF NOT EXISTS polybot.executor_order_cancel (
  ts DateTime64(3),
  order_id String,
  mode LowCardinality(String),
  success UInt8,
  error_type String,
  error_upstream_status Nullable(Int32),
  error_upstream_method String,
  error_upstream_url String,
  error_message String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (order_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.executor_order_cancel_mv
TO polybot.executor_order_cancel
AS
SELECT
  ts,
  JSONExtractString(data, 'orderId') AS order_id,
  JSONExtractString(data, 'mode') AS mode,
  ifNull(toUInt8(JSONExtractBool(data, 'success')), 0) AS success,
  JSONExtractString(data, 'error', 'type') AS error_type,
  ifNull(JSONExtractInt(data, 'error', 'upstreamStatus'), CAST(NULL, 'Nullable(Int32)')) AS error_upstream_status,
  JSONExtractString(data, 'error', 'upstreamMethod') AS error_upstream_method,
  JSONExtractString(data, 'error', 'upstreamUrl') AS error_upstream_url,
  JSONExtractString(data, 'error', 'message') AS error_message,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'executor.order.cancel';

