-- Improved on-chain pairing / routing labels for user trades.
--
-- Key fix:
-- - Pairing cannot require pair_shares == trade_shares, because a single trade can be a hybrid
--   (partial mint match + partial secondary fill) within the same tx.
-- - Instead, detect the paired leg from ConditionalTokens (ERC1155) transfers: the other outcome
--   transferred by the exchange to a different recipient in the same tx.

CREATE OR REPLACE VIEW polybot.user_trade_onchain_pair AS
WITH
  lower('0x4bfb41d5b3570defd03c39a9a4d8de6bd8b8982e') AS exchange
SELECT
  p.username,
  p.event_key,
  p.ts,
  p.proxy_address,
  p.market_slug,
  p.title,
  p.token_id,
  p.condition_id,
  p.side,
  p.outcome,
  p.outcome_index,
  p.price,
  p.size,
  p.tx_hash,

  p.onchain_usdc,
  p.onchain_shares,
  p.onchain_price,
  p.onchain_minus_trade_price,

  p.pair_token_id,
  nullIf(argMinIf(f.usdc_amount, abs(f.shares_amount - p.pair_shares), f.token_id = p.pair_token_id AND f.shares_amount > 0), 0) AS pair_usdc,
  p.pair_shares,
  p.pair_counterparty,
  round(pair_usdc / nullIf(p.pair_shares, 0), 6) AS pair_price,
  round(p.onchain_price + pair_price, 6) AS price_sum

FROM (
  SELECT
    u.username AS username,
    u.event_key AS event_key,
    u.ts AS ts,
    u.proxy_address AS proxy_address,
    u.market_slug AS market_slug,
    u.title AS title,
    u.token_id AS token_id,
    u.condition_id AS condition_id,
    u.side AS side,
    u.outcome AS outcome,
    u.outcome_index AS outcome_index,
    u.price AS price,
    u.size AS size,
    u.transaction_hash AS tx_hash,

    m.usdc_amount AS onchain_usdc,
    m.shares_amount AS onchain_shares,
    round(m.usdc_amount / nullIf(m.shares_amount, 0), 6) AS onchain_price,
    round(onchain_price - u.price, 6) AS onchain_minus_trade_price,

    argMinIf(
      ct.token_id,
      abs(ct.shares_amount - m.shares_amount),
      ct.from_address = exchange
        AND ct.to_address != lower(u.proxy_address)
        AND ct.to_address != exchange
        AND ct.token_id != u.token_id
        AND ct.shares_amount > 0
        AND (length(g.token_ids) = 0 OR has(g.token_ids, ct.token_id))
    ) AS pair_token_id,
    nullIf(argMinIf(
      ct.shares_amount,
      abs(ct.shares_amount - m.shares_amount),
      ct.from_address = exchange
        AND ct.to_address != lower(u.proxy_address)
        AND ct.to_address != exchange
        AND ct.token_id != u.token_id
        AND ct.shares_amount > 0
        AND (length(g.token_ids) = 0 OR has(g.token_ids, ct.token_id))
    ), 0) AS pair_shares,
    nullIf(argMinIf(
      ct.to_address,
      abs(ct.shares_amount - m.shares_amount),
      ct.from_address = exchange
        AND ct.to_address != lower(u.proxy_address)
        AND ct.to_address != exchange
        AND ct.token_id != u.token_id
        AND ct.shares_amount > 0
        AND (length(g.token_ids) = 0 OR has(g.token_ids, ct.token_id))
    ), '') AS pair_counterparty

  FROM polybot.user_trades_dedup u
  INNER JOIN polybot.polygon_exchange_orders_matched m
    ON m.tx_hash = u.transaction_hash
    AND m.user_address = lower(u.proxy_address)
    AND m.token_id = u.token_id
  LEFT JOIN polybot.gamma_markets_latest g
    ON g.slug = u.market_slug
  LEFT JOIN polybot.polygon_ctf_transfers ct
    ON ct.tx_hash = u.transaction_hash
  WHERE u.transaction_hash != ''
  GROUP BY
    u.username,
    u.event_key,
    u.ts,
    u.proxy_address,
    u.market_slug,
    u.title,
    u.token_id,
    u.condition_id,
    u.side,
    u.outcome,
    u.outcome_index,
    u.price,
    u.size,
    u.transaction_hash,
    m.usdc_amount,
    m.shares_amount
) p
LEFT JOIN polybot.polygon_exchange_order_filled f
  ON f.tx_hash = p.tx_hash
GROUP BY
  p.username,
  p.event_key,
  p.ts,
  p.proxy_address,
  p.market_slug,
  p.title,
  p.token_id,
  p.condition_id,
  p.side,
  p.outcome,
  p.outcome_index,
  p.price,
  p.size,
  p.tx_hash,
  p.onchain_usdc,
  p.onchain_shares,
  p.onchain_price,
  p.onchain_minus_trade_price,
  p.pair_token_id,
  p.pair_shares,
  p.pair_counterparty;
