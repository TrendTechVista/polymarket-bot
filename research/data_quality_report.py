#!/usr/bin/env python3
"""
Quick "are we collecting the right data?" report.

Focuses on the two biggest gaps for reverse-engineering accuracy:
- decision-time market state (WS TOB vs trade-triggered TOB, which is very stale)
- on-chain receipts (needed for fees + log decoding)
"""

from __future__ import annotations

import argparse
from datetime import datetime

import clickhouse_connect


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--username", default=os.getenv("POLYMARKET_TARGET_USER", "TARGET_USER"))
    ap.add_argument("--lookback-minutes", type=int, default=60)
    args = ap.parse_args()

    user = args.username
    lookback = max(1, int(args.lookback_minutes))

    client = clickhouse_connect.get_client(host="localhost", port=8123, database="polybot")

    print("=" * 80)
    print("DATA QUALITY REPORT")
    print(f"Timestamp: {datetime.utcnow().isoformat(timespec='seconds')}Z")
    print(f"Username:  {user}")
    print(f"Lookback:  {lookback} minutes")
    print("=" * 80)

    tables = set(r[0] for r in client.query("SHOW TABLES").result_rows)
    has_v4 = "user_trade_enriched_v4" in tables
    has_v3 = "user_trade_enriched_v3" in tables
    has_ws = has_v4 or has_v3
    view = "user_trade_enriched_v4" if has_v4 else ("user_trade_enriched_v3" if has_v3 else "user_trade_enriched_v2")

    print(f"\nUsing view: {view}")

    # Core dataset
    r = client.query(
        f"""
        SELECT
          count() AS trades,
          countIf(settle_price IS NOT NULL) AS resolved,
          countIf(seconds_to_end IS NOT NULL) AS with_timing
        FROM {view}
        WHERE username = '{user}'
        """
    ).result_rows[0]
    print("\n=== Trades ===")
    print(f"Trades:    {int(r[0]):,}")
    print(f"Resolved:  {int(r[1]):,}")
    print(f"Timing:    {int(r[2]):,}")

    # Trade-triggered TOB lag
    r = client.query(
        f"""
        SELECT
          count() AS n,
          quantileExact(0.5)(abs(dateDiff('millisecond', tob_captured_at, ts))) AS p50_abs_lag_ms,
          quantileExact(0.9)(abs(dateDiff('millisecond', tob_captured_at, ts))) AS p90_abs_lag_ms,
          quantileExact(0.99)(abs(dateDiff('millisecond', tob_captured_at, ts))) AS p99_abs_lag_ms
        FROM {view}
        WHERE username = '{user}'
          AND tob_captured_at > toDateTime64('2000-01-01 00:00:00',3)
        """
    ).result_rows[0]
    print("\n=== TOB Snapshot Lag (trade-triggered) ===")
    print(f"Rows:   {int(r[0]):,}")
    print(f"P50:    {int(r[1]):,} ms")
    print(f"P90:    {int(r[2]):,} ms")
    print(f"P99:    {int(r[3]):,} ms")

    # WS TOB coverage (only if WS-enriched views exist)
    if has_ws:
        r = client.query(
            f"""
            SELECT
              count() AS trades,
              countIf(ws_tob_lag_millis IS NOT NULL) AS with_ws,
              round(with_ws * 100.0 / trades, 2) AS pct_with_ws,
              countIf(ws_tob_lag_millis <= 500) AS with_ws_le_500ms,
              round(with_ws_le_500ms * 100.0 / trades, 2) AS pct_with_ws_le_500ms,
              countIf(ws_tob_lag_millis <= 2000) AS with_ws_le_2s,
              round(with_ws_le_2s * 100.0 / trades, 2) AS pct_with_ws_le_2s,
              quantileExact(0.5)(ws_tob_lag_millis) AS p50_ws_lag_ms,
              quantileExact(0.9)(ws_tob_lag_millis) AS p90_ws_lag_ms
            FROM {view}
            WHERE username = '{user}'
              AND ts >= now() - INTERVAL {lookback} MINUTE
            """
        ).result_rows[0]
        print("\n=== WS TOB Coverage (ASOF join) ===")
        print(f"Trades (lookback): {int(r[0]):,}")
        print(f"With WS:           {int(r[1]):,} ({float(r[2]):.2f}%)")
        print(f"With WS <= 500ms:  {int(r[3]):,} ({float(r[4]):.2f}%)")
        print(f"With WS <= 2s:     {int(r[5]):,} ({float(r[6]):.2f}%)")
        if r[7] is not None:
            print(f"WS lag P50:        {int(r[7]):,} ms")
            print(f"WS lag P90:        {int(r[8]):,} ms")

        r = client.query(
            f"""
            SELECT
              ws_exec_type,
              count() AS n
            FROM {view}
            WHERE username = '{user}'
              AND ts >= now() - INTERVAL {lookback} MINUTE
              AND ws_tob_lag_millis IS NOT NULL
            GROUP BY ws_exec_type
            ORDER BY n DESC
            """
        )
        if r.result_rows:
            print("\nWS exec mix (lookback):")
            for row in r.result_rows:
                print(f"  {row[0]:<12} {int(row[1]):,}")

    # Polygon receipts coverage (if enabled)
    r = client.query(
        f"""
        SELECT
          countIf(tx_block_number IS NOT NULL) AS trades_with_receipt_join,
          minIf(ts, tx_block_number IS NOT NULL) AS first_trade_with_receipt,
          maxIf(ts, tx_block_number IS NOT NULL) AS last_trade_with_receipt
        FROM {view}
        WHERE username = '{user}'
        """
    ).result_rows[0]
    print("\n=== Polygon Receipt Join (trades) ===")
    print(f"Trades w/receipt:  {int(r[0]):,}")
    print(f"First trade w/tx:  {r[1]}")
    print(f"Last trade w/tx:   {r[2]}")

    r = client.query(
        f"""
        SELECT
          countDistinct(u.transaction_hash) AS total_txs,
          countDistinctIf(u.transaction_hash, pr.tx_hash != '') AS txs_with_receipts,
          (total_txs - txs_with_receipts) AS missing_txs
        FROM polybot.user_trades_dedup u
        LEFT JOIN polybot.polygon_tx_receipts_latest pr
          ON pr.tx_hash = lower(u.transaction_hash)
        WHERE u.username = '{user}'
          AND u.transaction_hash != ''
        """
    ).result_rows[0]
    print("\nReceipt backlog (tx-level):")
    print(f"Tx hashes total:    {int(r[0]):,}")
    print(f"Txs with receipts:  {int(r[1]):,}")
    print(f"Txs missing:        {int(r[2]):,}")

    r = client.query(
        f"""
        SELECT
          count() AS receipt_rows,
          countDistinct(tx_hash) AS distinct_txs,
          max(block_timestamp) AS last_block_ts
        FROM polybot.polygon_tx_receipts
        """
    ).result_rows[0]
    print("\n=== Polygon Receipts Table ===")
    print(f"Rows:      {int(r[0]):,}")
    print(f"Tx hashes: {int(r[1]):,}")
    print(f"Last blk:  {r[2]}")

    # NOTE: tx_from is typically a relayer, not the trader.
    r = client.query(
        f"""
        SELECT
          count() AS trades_with_receipt,
          countIf(lower(tx_from_address) = lower(proxy_address)) AS from_matches_proxy
        FROM {view}
        WHERE username = '{user}'
          AND tx_from_address IS NOT NULL
        """
    ).result_rows[0]
    if int(r[0]) > 0:
        print("\nReceipt tx.from vs proxy (should usually be 0):")
        print(f"Trades w/receipt: {int(r[0]):,}")
    print(f"from==proxy:      {int(r[1]):,}")

    # Bot order lifecycle (populates when strategy/executor are running)
    r = client.query(
        f"""
        SELECT
          count() AS gabagool_order_events,
          countIf(action = 'PLACE') AS places,
          countIf(action = 'CANCEL') AS cancels,
          countIf(action = 'STATUS') AS status_polls
        FROM polybot.strategy_gabagool_orders
        WHERE ts >= now() - INTERVAL {lookback} MINUTE
        """
    ).result_rows[0]
    print("\n=== Bot Order Lifecycle (lookback) ===")
    print(f"strategy_gabagool_orders: {int(r[0]):,} (place={int(r[1]):,} cancel={int(r[2]):,} status={int(r[3]):,})")

    r = client.query(
        f"""
        SELECT
          count() AS status_events,
          countIf(exchange_status ILIKE '%FILLED%') AS filled,
          countIf(exchange_status ILIKE '%CANCEL%') AS canceled
        FROM polybot.executor_order_status
        WHERE ts >= now() - INTERVAL {lookback} MINUTE
        """
    ).result_rows[0]
    print(f"executor_order_status:     {int(r[0]):,} (filled={int(r[1]):,} canceled={int(r[2]):,})")

    print("\nDone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
