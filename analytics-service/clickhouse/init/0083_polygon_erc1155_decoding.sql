-- Polygon ERC1155 log decoding (TransferSingle / TransferBatch).
--
-- Primary use-case: decode ConditionalTokens (ERC1155) transfers to understand
-- whether a tx was a "mint match" (new positions minted to exchange, then distributed)
-- vs secondary-market transfers between users.

-- ERC1155 TransferSingle topic0: keccak256("TransferSingle(address,address,address,uint256,uint256)")
CREATE OR REPLACE VIEW polybot.polygon_erc1155_transfer_single AS
WITH
  lower('0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62') AS sig
SELECT
  r.tx_hash,
  r.block_timestamp,
  lower(JSONExtractString(l, 'address')) AS token_address,
  JSONExtract(l, 'topics', 'Array(String)') AS topics,
  lower(concat('0x', right(topics[2], 40))) AS operator,
  lower(concat('0x', right(topics[3], 40))) AS from_address,
  lower(concat('0x', right(topics[4], 40))) AS to_address,
  reinterpretAsUInt256(reverse(unhex(substring(JSONExtractString(l, 'data'), 3 + 0 * 64, 64)))) AS id_u256,
  toString(id_u256) AS token_id,
  reinterpretAsUInt256(reverse(unhex(substring(JSONExtractString(l, 'data'), 3 + 1 * 64, 64)))) AS value_raw
FROM polybot.polygon_tx_receipts_latest r
ARRAY JOIN JSONExtractArrayRaw(r.receipt_json, 'logs') AS l
WHERE lower(JSONExtract(l, 'topics', 'Array(String)')[1]) = sig
  AND length(JSONExtract(l, 'topics', 'Array(String)')) >= 4
  AND JSONExtractString(l, 'data') LIKE '0x%';

-- ERC1155 TransferBatch topic0: keccak256("TransferBatch(address,address,address,uint256[],uint256[])")
CREATE OR REPLACE VIEW polybot.polygon_erc1155_transfer_batch AS
WITH
  lower('0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb') AS sig
SELECT
  tx_hash,
  block_timestamp,
  token_address,
  topics,
  operator,
  from_address,
  to_address,
  ids,
  values_raw
FROM (
  SELECT
    r.tx_hash AS tx_hash,
    r.block_timestamp AS block_timestamp,
    lower(JSONExtractString(l, 'address')) AS token_address,
    JSONExtract(l, 'topics', 'Array(String)') AS topics,
    lower(concat('0x', right(topics[2], 40))) AS operator,
    lower(concat('0x', right(topics[3], 40))) AS from_address,
    lower(concat('0x', right(topics[4], 40))) AS to_address,
    JSONExtractString(l, 'data') AS data,
    toInt64(reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 0 * 64, 64))))) / 32 AS ids_offset_words,
    toInt64(reinterpretAsUInt256(reverse(unhex(substring(data, 3 + 1 * 64, 64))))) / 32 AS values_offset_words,
    toInt64(reinterpretAsUInt256(reverse(unhex(substring(data, 3 + ids_offset_words * 64, 64))))) AS ids_len,
    toInt64(reinterpretAsUInt256(reverse(unhex(substring(data, 3 + values_offset_words * 64, 64))))) AS values_len,
    arrayMap(i -> toString(reinterpretAsUInt256(reverse(unhex(substring(data, 3 + (ids_offset_words + 1 + i) * 64, 64))))), range(greatest(ids_len, 0))) AS ids,
    arrayMap(i -> reinterpretAsUInt256(reverse(unhex(substring(data, 3 + (values_offset_words + 1 + i) * 64, 64)))), range(greatest(values_len, 0))) AS values_raw
  FROM polybot.polygon_tx_receipts_latest r
  ARRAY JOIN JSONExtractArrayRaw(r.receipt_json, 'logs') AS l
  WHERE lower(JSONExtract(l, 'topics', 'Array(String)')[1]) = sig
    AND length(JSONExtract(l, 'topics', 'Array(String)')) >= 4
    AND JSONExtractString(l, 'data') LIKE '0x%'
);

-- ConditionalTokens contract on Polygon (Polymarket).
CREATE OR REPLACE VIEW polybot.polygon_ctf_transfer_single AS
WITH lower('0x4d97dcd97ec945f40cf65f87097ace5ea0476045') AS conditional_tokens
SELECT
  tx_hash,
  block_timestamp,
  operator,
  from_address,
  to_address,
  token_id,
  value_raw,
  toFloat64(value_raw) / 1000000.0 AS shares_amount
FROM polybot.polygon_erc1155_transfer_single
WHERE token_address = conditional_tokens;

CREATE OR REPLACE VIEW polybot.polygon_ctf_transfer_batch AS
WITH lower('0x4d97dcd97ec945f40cf65f87097ace5ea0476045') AS conditional_tokens
SELECT
  tx_hash,
  block_timestamp,
  operator,
  from_address,
  to_address,
  ids,
  values_raw
FROM polybot.polygon_erc1155_transfer_batch
WHERE token_address = conditional_tokens;

-- Explode TransferBatch into one-row-per-(token_id,value) for ConditionalTokens.
CREATE OR REPLACE VIEW polybot.polygon_ctf_transfer_batch_items AS
SELECT
  tx_hash,
  block_timestamp,
  operator,
  from_address,
  to_address,
  arrayElement(ids, i) AS token_id,
  arrayElement(values_raw, i) AS value_raw,
  toFloat64(value_raw) / 1000000.0 AS shares_amount
FROM polybot.polygon_ctf_transfer_batch
ARRAY JOIN arrayEnumerate(ids) AS i;

-- Unified ConditionalTokens transfers.
CREATE OR REPLACE VIEW polybot.polygon_ctf_transfers AS
SELECT tx_hash, block_timestamp, operator, from_address, to_address, token_id, value_raw, shares_amount
FROM polybot.polygon_ctf_transfer_single
UNION ALL
SELECT tx_hash, block_timestamp, operator, from_address, to_address, token_id, value_raw, shares_amount
FROM polybot.polygon_ctf_transfer_batch_items;

