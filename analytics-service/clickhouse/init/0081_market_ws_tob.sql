-- Market WS top-of-book snapshots (continuous, low-latency).
--
-- Produced by polybot-core's ClobMarketWebSocketClient as event type: market_ws.tob
-- Useful for ASOF joins to trades to reduce TOB staleness vs "TOB-on-trade-arrival" snapshots.

CREATE TABLE IF NOT EXISTS polybot.market_ws_tob (
  ts DateTime64(3),
  captured_at DateTime64(3),
  asset_id String,
  best_bid_price Float64,
  best_bid_size Float64,
  best_ask_price Float64,
  best_ask_size Float64,
  last_trade_price Float64,
  tob_updated_at DateTime64(3),
  last_trade_at Nullable(DateTime64(3)),
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (asset_id, ts, kafka_partition, kafka_offset);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.market_ws_tob_mv
TO polybot.market_ws_tob
AS
SELECT
  ts,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'updatedAt')), ts) AS captured_at,
  JSONExtractString(data, 'assetId') AS asset_id,
  JSONExtractFloat(data, 'bestBid') AS best_bid_price,
  JSONExtractFloat(data, 'bestBidSize') AS best_bid_size,
  JSONExtractFloat(data, 'bestAsk') AS best_ask_price,
  JSONExtractFloat(data, 'bestAskSize') AS best_ask_size,
  JSONExtractFloat(data, 'lastTradePrice') AS last_trade_price,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'updatedAt')), ts) AS tob_updated_at,
  parseDateTime64BestEffortOrNull(JSONExtractString(data, 'lastTradeAt')) AS last_trade_at,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'market_ws.tob';

CREATE OR REPLACE VIEW polybot.market_ws_tob_latest AS
SELECT
  asset_id,
  argMax(best_bid_price, ts) AS best_bid_price,
  argMax(best_bid_size, ts) AS best_bid_size,
  argMax(best_ask_price, ts) AS best_ask_price,
  argMax(best_ask_size, ts) AS best_ask_size,
  argMax(last_trade_price, ts) AS last_trade_price,
  max(ts) AS tob_ts
FROM polybot.market_ws_tob
GROUP BY asset_id;

