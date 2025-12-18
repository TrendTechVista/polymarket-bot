-- Executor order-status snapshots (polled).
--
-- Captures live lifecycle transitions (open → partial → filled/canceled) so we can:
-- - calibrate unfilled/cancel cadence vs fills
-- - avoid relying solely on post-hoc user trade prints

CREATE TABLE IF NOT EXISTS polybot.executor_order_status (
  ts DateTime64(3),
  order_id String,
  token_id String,
  side LowCardinality(String),
  requested_price Nullable(Float64),
  requested_size Nullable(Float64),
  exchange_status LowCardinality(String),
  matched_size Nullable(Float64),
  remaining_size Nullable(Float64),
  order_json String,
  error String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (order_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.executor_order_status_mv
TO polybot.executor_order_status
AS
SELECT
  ts,
  JSONExtractString(data, 'orderId') AS order_id,
  JSONExtractString(data, 'tokenId') AS token_id,
  JSONExtractString(data, 'side') AS side,
  nullIf(JSONExtractFloat(data, 'requestedPrice'), 0) AS requested_price,
  nullIf(JSONExtractFloat(data, 'requestedSize'), 0) AS requested_size,
  JSONExtractString(data, 'exchangeStatus') AS exchange_status,
  nullIf(JSONExtractFloat(data, 'matchedSize'), 0) AS matched_size,
  nullIf(JSONExtractFloat(data, 'remainingSize'), 0) AS remaining_size,
  JSONExtractString(data, 'orderJson') AS order_json,
  JSONExtractString(data, 'error') AS error,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'executor.order.status';

