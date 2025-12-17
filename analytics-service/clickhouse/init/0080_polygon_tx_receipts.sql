-- Polygon on-chain transaction receipts (enrichment for trade tx hashes).

CREATE TABLE IF NOT EXISTS polybot.polygon_tx_receipts (
  ts DateTime64(3),
  captured_at DateTime64(3),
  chain_id UInt32,
  tx_hash String,
  block_number UInt64,
  block_timestamp DateTime64(3),
  status UInt8,
  from_address String,
  to_address String,
  gas_used UInt64,
  effective_gas_price UInt64,
  receipt_json String,
  event_key String,
  ingested_at DateTime64(3),
  kafka_partition Int32,
  kafka_offset Int64,
  kafka_timestamp DateTime64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toDate(ts)
ORDER BY (tx_hash, ingested_at);

CREATE MATERIALIZED VIEW IF NOT EXISTS polybot.polygon_tx_receipts_mv
TO polybot.polygon_tx_receipts
AS
SELECT
  ts,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'capturedAt')), ts) AS captured_at,
  toUInt32(JSONExtractInt(data, 'chainId')) AS chain_id,
  JSONExtractString(data, 'txHash') AS tx_hash,
  toUInt64(JSONExtractInt(data, 'blockNumber')) AS block_number,
  ifNull(parseDateTime64BestEffortOrNull(JSONExtractString(data, 'blockTimestamp')), ts) AS block_timestamp,
  toUInt8(JSONExtractInt(data, 'status')) AS status,
  JSONExtractString(data, 'from') AS from_address,
  JSONExtractString(data, 'to') AS to_address,
  toUInt64(JSONExtractInt(data, 'gasUsed')) AS gas_used,
  toUInt64OrZero(JSONExtractString(data, 'effectiveGasPrice')) AS effective_gas_price,
  ifNull(JSONExtractRaw(data, 'receipt'), '{}') AS receipt_json,
  kafka_key AS event_key,
  ingested_at,
  kafka_partition,
  kafka_offset,
  kafka_timestamp
FROM polybot.analytics_events
WHERE type = 'polygon.tx.receipt';

CREATE OR REPLACE VIEW polybot.polygon_tx_receipts_latest AS
SELECT
  tx_hash,
  argMax(chain_id, ingested_at) AS chain_id,
  argMax(block_number, ingested_at) AS block_number,
  argMax(block_timestamp, ingested_at) AS block_timestamp,
  argMax(status, ingested_at) AS status,
  argMax(from_address, ingested_at) AS from_address,
  argMax(to_address, ingested_at) AS to_address,
  argMax(gas_used, ingested_at) AS gas_used,
  argMax(effective_gas_price, ingested_at) AS effective_gas_price,
  argMax(receipt_json, ingested_at) AS receipt_json,
  max(ingested_at) AS latest_ingested_at
FROM polybot.polygon_tx_receipts
GROUP BY tx_hash;
