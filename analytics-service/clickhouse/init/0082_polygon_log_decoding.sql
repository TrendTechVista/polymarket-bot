-- Lightweight Polygon receipt log decoding (ClickHouse views).
--
-- Goal: extract the few on-chain facts we need for reverse-engineering:
-- - USDC flows per transaction (fees + net spend/receive)
-- - (optionally later) ERC1155/Exchange events

-- ERC20 Transfer topic0: keccak256("Transfer(address,address,uint256)")
CREATE OR REPLACE VIEW polybot.polygon_erc20_transfers AS
WITH
  lower('0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef') AS transfer_sig
SELECT
  r.tx_hash,
  r.block_timestamp,
  lower(JSONExtractString(l, 'address')) AS token_address,
  JSONExtract(l, 'topics', 'Array(String)') AS topics,
  lower(concat('0x', right(topics[2], 40))) AS from_address,
  lower(concat('0x', right(topics[3], 40))) AS to_address,
  reinterpretAsUInt256(reverse(unhex(substring(JSONExtractString(l, 'data'), 3)))) AS amount_raw
FROM polybot.polygon_tx_receipts_latest r
ARRAY JOIN JSONExtractArrayRaw(r.receipt_json, 'logs') AS l
WHERE lower(JSONExtract(l, 'topics', 'Array(String)')[1]) = transfer_sig
  AND length(JSONExtract(l, 'topics', 'Array(String)')) >= 3
  AND JSONExtractString(l, 'data') LIKE '0x%';

-- USDC contract on Polygon.
CREATE OR REPLACE VIEW polybot.polygon_usdc_transfers AS
WITH lower('0x2791bca1f2de4661ed88a30c99a7a9449aa84174') AS usdc
SELECT
  tx_hash,
  block_timestamp,
  from_address,
  to_address,
  amount_raw,
  toFloat64(amount_raw) / 1000000.0 AS amount_usdc
FROM polybot.polygon_erc20_transfers
WHERE token_address = usdc;

-- Aggregate user trades to tx-level expected USDC notionals (fills-based).
CREATE OR REPLACE VIEW polybot.user_tx_trade_summary AS
SELECT
  username,
  proxy_address,
  transaction_hash AS tx_hash,
  count() AS trade_count,
  round(sumIf(price * size, side = 'BUY'), 6) AS expected_usdc_out,
  round(sumIf(price * size, side = 'SELL'), 6) AS expected_usdc_in,
  round(expected_usdc_in - expected_usdc_out, 6) AS expected_usdc_net
FROM polybot.user_trades_dedup
WHERE transaction_hash != ''
GROUP BY username, proxy_address, tx_hash;

-- Join on-chain USDC flows to fills-based expected notionals.
CREATE OR REPLACE VIEW polybot.user_tx_usdc_summary AS
SELECT
  t.username,
  t.proxy_address,
  t.tx_hash,
  t.trade_count,
  t.expected_usdc_out,
  t.expected_usdc_in,
  t.expected_usdc_net,
  round(sumIf(p.amount_usdc, p.from_address = lower(t.proxy_address)), 6) AS actual_usdc_out,
  round(sumIf(p.amount_usdc, p.to_address = lower(t.proxy_address)), 6) AS actual_usdc_in,
  round(actual_usdc_in - actual_usdc_out, 6) AS actual_usdc_net,
  round(actual_usdc_out - expected_usdc_out, 6) AS usdc_out_diff,
  round(actual_usdc_in - expected_usdc_in, 6) AS usdc_in_diff
FROM polybot.user_tx_trade_summary t
LEFT JOIN polybot.polygon_usdc_transfers p
  ON p.tx_hash = t.tx_hash
GROUP BY
  t.username,
  t.proxy_address,
  t.tx_hash,
  t.trade_count,
  t.expected_usdc_out,
  t.expected_usdc_in,
  t.expected_usdc_net;

-- Polymarket exchange-level logs (useful to identify complete-set mint matches and maker/taker identities).
--
-- Exchange log address observed in receipts (emits OrderFilled/OrdersMatched):
--   0x4bfb41d5b3570defd03c39a9a4d8de6bd8b8982e

-- OrderFilled topic0: keccak256("OrderFilled(bytes32,address,address,uint256,uint256,uint256,uint256,uint256)")
CREATE OR REPLACE VIEW polybot.polygon_exchange_order_filled AS
WITH
  lower('0x4bfb41d5b3570defd03c39a9a4d8de6bd8b8982e') AS exchange,
  lower('0xd0a08e8c493f9c94f29311604c9de1b4e8c8d4c06bd0c789af57f2d65bfec0f6') AS sig
SELECT
  tx_hash,
  block_timestamp,
  topics[2] AS order_hash,
  lower(concat('0x', right(topics[3], 40))) AS addr1,
  lower(concat('0x', right(topics[4], 40))) AS addr2,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 0 * 64, 64)))) AS w1,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 1 * 64, 64)))) AS token_id_u256,
  toString(token_id_u256) AS token_id,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 2 * 64, 64)))) AS usdc_amount_raw,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 3 * 64, 64)))) AS shares_amount_raw,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 4 * 64, 64)))) AS w5,
  toFloat64(usdc_amount_raw) / 1000000.0 AS usdc_amount,
  toFloat64(shares_amount_raw) / 1000000.0 AS shares_amount
FROM (
  SELECT
    r.tx_hash AS tx_hash,
    r.block_timestamp AS block_timestamp,
    JSONExtract(l, 'topics', 'Array(String)') AS topics,
    JSONExtractString(l, 'data') AS data
  FROM polybot.polygon_tx_receipts_latest r
  ARRAY JOIN JSONExtractArrayRaw(r.receipt_json, 'logs') AS l
  WHERE lower(JSONExtractString(l, 'address')) = exchange
    AND lower(JSONExtract(l, 'topics', 'Array(String)')[1]) = sig
    AND length(JSONExtract(l, 'topics', 'Array(String)')) >= 4
    AND JSONExtractString(l, 'data') LIKE '0x%'
);

-- OrdersMatched topic0: keccak256("OrdersMatched(bytes32,address,uint256,uint256,uint256,uint256)")
CREATE OR REPLACE VIEW polybot.polygon_exchange_orders_matched AS
WITH
  lower('0x4bfb41d5b3570defd03c39a9a4d8de6bd8b8982e') AS exchange,
  lower('0x63bf4d16b7fa898ef4c4b2b6d90fd201e9c56313b65638af6088d149d2ce956c') AS sig
SELECT
  tx_hash,
  block_timestamp,
  topics[2] AS match_id,
  lower(concat('0x', right(topics[3], 40))) AS user_address,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 0 * 64, 64)))) AS w1,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 1 * 64, 64)))) AS token_id_u256,
  toString(token_id_u256) AS token_id,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 2 * 64, 64)))) AS usdc_amount_raw,
  reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 3 * 64, 64)))) AS shares_amount_raw,
  toFloat64(usdc_amount_raw) / 1000000.0 AS usdc_amount,
  toFloat64(shares_amount_raw) / 1000000.0 AS shares_amount
FROM (
  SELECT
    r.tx_hash AS tx_hash,
    r.block_timestamp AS block_timestamp,
    JSONExtract(l, 'topics', 'Array(String)') AS topics,
    JSONExtractString(l, 'data') AS data
  FROM polybot.polygon_tx_receipts_latest r
  ARRAY JOIN JSONExtractArrayRaw(r.receipt_json, 'logs') AS l
  WHERE lower(JSONExtractString(l, 'address')) = exchange
    AND lower(JSONExtract(l, 'topics', 'Array(String)')[1]) = sig
    AND length(JSONExtract(l, 'topics', 'Array(String)')) >= 3
    AND JSONExtractString(l, 'data') LIKE '0x%'
);

-- Join fills-based trades to on-chain OrdersMatched/OrderFilled, and surface the paired-leg fill
-- (typically the opposite outcome in complete-set mints).
CREATE OR REPLACE VIEW polybot.user_trade_onchain_pair AS
SELECT
  u.username,
  u.event_key,
  u.ts,
  u.proxy_address,
  u.market_slug,
  u.title,
  u.token_id AS token_id,
  u.condition_id,
  u.side,
  u.outcome,
  u.outcome_index,
  u.price,
  u.size,
  u.transaction_hash AS tx_hash,

  m.usdc_amount AS onchain_usdc,
  m.shares_amount AS onchain_shares,
  round(m.usdc_amount / nullIf(m.shares_amount, 0), 6) AS onchain_price,
  round(onchain_price - u.price, 6) AS onchain_minus_trade_price,

  anyIf(f.token_id, f.token_id != u.token_id AND f.token_id != '0' AND f.addr2 = lower(u.proxy_address) AND f.shares_amount = m.shares_amount) AS pair_token_id,
  anyIf(f.usdc_amount, f.token_id != u.token_id AND f.token_id != '0' AND f.addr2 = lower(u.proxy_address) AND f.shares_amount = m.shares_amount) AS pair_usdc,
  anyIf(f.shares_amount, f.token_id != u.token_id AND f.token_id != '0' AND f.addr2 = lower(u.proxy_address) AND f.shares_amount = m.shares_amount) AS pair_shares,
  anyIf(f.addr1, f.token_id != u.token_id AND f.token_id != '0' AND f.addr2 = lower(u.proxy_address) AND f.shares_amount = m.shares_amount) AS pair_counterparty,
  round(pair_usdc / nullIf(pair_shares, 0), 6) AS pair_price,
  round(onchain_price + pair_price, 6) AS price_sum

FROM polybot.user_trades_dedup u
INNER JOIN polybot.polygon_exchange_orders_matched m
  ON m.tx_hash = u.transaction_hash
  AND m.user_address = lower(u.proxy_address)
  AND m.token_id = u.token_id
LEFT JOIN polybot.polygon_exchange_order_filled f
  ON f.tx_hash = u.transaction_hash
WHERE u.transaction_hash != ''
GROUP BY
  u.username,
  u.event_key,
  u.ts,
  u.proxy_address,
  u.market_slug,
  u.title,
  token_id,
  u.condition_id,
  u.side,
  u.outcome,
  u.outcome_index,
  u.price,
  u.size,
  tx_hash,
  onchain_usdc,
  onchain_shares,
  onchain_price,
  onchain_minus_trade_price;
