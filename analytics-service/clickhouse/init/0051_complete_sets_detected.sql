-- Individual complete-set (Up+Down / Yes+No) pair detection within a small time window.
--
-- This is primarily for research/diagnostics; it can be expensive for large datasets.
-- We keep the JOIN condition purely equi-join and push the time window constraints into WHERE
-- so it runs without requiring allow_experimental_join_condition.

CREATE OR REPLACE VIEW polybot.user_complete_sets_detected AS
WITH
  60 AS window_seconds,
  pairs AS (
    SELECT
      u1.username AS username,
      u1.market_slug AS market_slug,
      u1.ts AS ts_1,
      u1.event_key AS event_key_1,
      u1.outcome AS outcome_1,
      u1.price AS price_1,
      u1.size AS size_1,
      u2.ts AS ts_2,
      u2.event_key AS event_key_2,
      u2.outcome AS outcome_2,
      u2.price AS price_2,
      u2.size AS size_2,
      abs(dateDiff('second', u1.ts, u2.ts)) AS time_gap_sec
    FROM polybot.user_trades_dedup AS u1
    INNER JOIN polybot.user_trades_dedup AS u2
      ON u1.username = u2.username
     AND u1.market_slug = u2.market_slug
     AND u1.condition_id = u2.condition_id
    WHERE u1.side = 'BUY'
      AND u2.side = 'BUY'
      AND u1.outcome IN ('Up', 'Down', 'Yes', 'No')
      AND u2.outcome IN ('Up', 'Down', 'Yes', 'No')
      AND u1.outcome != u2.outcome
      AND abs(dateDiff('second', u1.ts, u2.ts)) <= window_seconds
      AND u1.ts <= u2.ts
      AND u1.event_key < u2.event_key
  )
SELECT
  username,
  market_slug,
  ts_1,
  event_key_1,
  outcome_1,
  price_1,
  size_1,
  ts_2,
  event_key_2,
  outcome_2,
  price_2,
  size_2,
  time_gap_sec,
  least(size_1, size_2) AS matched_size,
  price_1 + price_2 AS combined_cost,
  1 - (price_1 + price_2) AS edge_per_share,
  least(size_1, size_2) * (1 - (price_1 + price_2)) AS edge_pnl
FROM pairs;

