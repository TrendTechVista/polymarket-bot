#!/usr/bin/env python3
"""
On-chain match report for a Polymarket user.

Uses ClickHouse views created in:
  analytics-service/clickhouse/init/0082_polygon_log_decoding.sql
"""

from __future__ import annotations

import argparse
from datetime import datetime

import clickhouse_connect


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--username", default=os.getenv("POLYMARKET_TARGET_USER", "TARGET_USER"))
    args = ap.parse_args()

    user = args.username
    client = clickhouse_connect.get_client(host="localhost", port=8123, database="polybot")

    print("=" * 80)
    print("ON-CHAIN MATCH REPORT")
    print(f"Timestamp: {datetime.utcnow().isoformat(timespec='seconds')}Z")
    print(f"Username:  {user}")
    print("=" * 80)

    tables = set(r[0] for r in client.query("SHOW TABLES").result_rows)
    enriched = (
        "user_trade_enriched_v4"
        if "user_trade_enriched_v4" in tables
        else ("user_trade_enriched_v3" if "user_trade_enriched_v3" in tables else "user_trade_enriched_v2")
    )

    # Coverage + complete-set-like share (based on paired outcome fill)
    r = client.query(
        f"""
        SELECT
          count() AS onchain_trades,
          countIf(pair_token_id != '') AS complete_set_like,
          round(complete_set_like * 100.0 / onchain_trades, 2) AS pct_complete_set_like,
          round(quantileExact(0.5)(abs(onchain_minus_trade_price)), 6) AS p50_abs_trade_vs_onchain_price
        FROM polybot.user_trade_onchain_pair
        WHERE username = '{user}'
        """
    ).result_rows[0]

    print("\n=== Coverage ===")
    print(f"On-chain matched trades:     {int(r[0]):,}")
    print(f"Complete-set-like trades:    {int(r[1]):,} ({float(r[2]):.2f}%)")
    print(f"P50 |tradePrice-onchain|:    {float(r[3]):.6f}")

    # PnL split (resolved only; on-chain subset)
    r = client.query(
        f"""
        SELECT
          (p.pair_token_id != '') AS complete_set_like,
          count() AS trades,
          round(sum(e.realized_pnl), 2) AS pnl,
          round(avg(e.realized_pnl), 4) AS avg_pnl,
          round(countIf(e.realized_pnl > 0) * 100.0 / trades, 2) AS win_rate
        FROM polybot.{enriched} e
        INNER JOIN polybot.user_trade_onchain_pair p
          ON p.event_key = e.event_key
        WHERE e.username = '{user}'
          AND e.settle_price IS NOT NULL
        GROUP BY complete_set_like
        ORDER BY complete_set_like
        """
    ).result_rows

    print("\n=== On-chain Subset PnL (resolved) ===")
    if not r:
        print("No resolved trades with on-chain matches yet.")
    else:
        print(f"Using view: {enriched}")
        print(f"{'completeSetLike':<16} {'trades':>8} {'pnl':>12} {'avg':>10} {'winRate':>9}")
        for row in r:
            print(f"{int(row[0]):<16} {int(row[1]):>8,} ${float(row[2]):>10,.2f} {float(row[3]):>10.4f} {float(row[4]):>8.2f}%")

    # Top counterparties for complete-set-like trades (optional, but very useful diagnostically)
    r = client.query(
        f"""
        SELECT
          pair_counterparty,
          count() AS n
        FROM polybot.user_trade_onchain_pair
        WHERE username = '{user}'
          AND pair_token_id != ''
        GROUP BY pair_counterparty
        ORDER BY n DESC
        LIMIT 10
        """
    ).result_rows

    print("\n=== Top Complete-Set Counterparties (by tx-count) ===")
    for addr, n in r:
        print(f"{addr}\t{int(n):,}")

    print("\nDone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
