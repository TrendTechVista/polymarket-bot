#!/usr/bin/env python3
"""
Compare a candidate bot username to target user (distribution-level "replication score").

This does NOT prove "exact" replication (fills don't expose cancellations/quotes), but it gives a
fast quantitative checklist to catch obvious mismatches:
- market universe + mix
- timing (seconds_to_end)
- sizing (shares + notional)
- outcome mix
- execution classification (prefer ws_exec_type from user_trade_enriched_v3 when available)
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

import clickhouse_connect
import pandas as pd


def _l1_distance(p: Dict[str, float], q: Dict[str, float]) -> float:
    keys = set(p) | set(q)
    return sum(abs(p.get(k, 0.0) - q.get(k, 0.0)) for k in keys)


def _normalize_counts(df: pd.DataFrame, key: str, count_col: str = "n") -> Dict[str, float]:
    if df.empty:
        return {}
    total = float(df[count_col].sum())
    if total <= 0:
        return {}
    out: Dict[str, float] = {}
    for _, row in df.iterrows():
        out[str(row[key])] = float(row[count_col]) / total
    return out


@dataclass(frozen=True)
class TableChoice:
    enriched_view: str
    exec_col: str


def _pick_enriched_view(client) -> TableChoice:
    # Prefer v4 (paired WS TOB), else v3, else v2.
    tables = set(r[0] for r in client.query("SHOW TABLES").result_rows)
    if "user_trade_enriched_v4" in tables:
        return TableChoice("user_trade_enriched_v4", "ws_exec_type")
    if "user_trade_enriched_v3" in tables:
        return TableChoice("user_trade_enriched_v3", "ws_exec_type")
    return TableChoice("user_trade_enriched_v2", "exec_type")


def _time_where(start_ts: Optional[str], end_ts: Optional[str]) -> str:
    parts = []
    if start_ts:
        parts.append(f"ts >= parseDateTime64BestEffort('{start_ts}')")
    if end_ts:
        parts.append(f"ts < parseDateTime64BestEffort('{end_ts}')")
    return (" AND " + " AND ".join(parts)) if parts else ""


def _mtype_expr() -> str:
    return """multiIf(
      market_slug LIKE 'btc-updown-15m-%', '15m-BTC',
      market_slug LIKE 'eth-updown-15m-%', '15m-ETH',
      market_slug LIKE 'bitcoin-up-or-down-%', '1h-BTC',
      market_slug LIKE 'ethereum-up-or-down-%', '1h-ETH',
      'other'
    )"""


def fetch_overview(client, view: str, username: str, where_time: str) -> Tuple[int, int, float]:
    q = f"""
    SELECT
      count() AS trades,
      countIf(is_resolved = 1) AS resolved,
      round(sum(price * size), 2) AS notional_usd
    FROM {view}
    WHERE username = '{username}' {where_time}
    """
    r = client.query(q).result_rows[0]
    return int(r[0]), int(r[1]), float(r[2])


def fetch_distribution(client, view: str, username: str, where_time: str, *, sql_key: str, col: str) -> pd.DataFrame:
    q = f"""
    SELECT {sql_key} AS {col}, count() AS n
    FROM {view}
    WHERE username = '{username}' {where_time}
    GROUP BY {col}
    ORDER BY n DESC
    """
    return client.query_df(q)


def fetch_timing_buckets(client, view: str, username: str, where_time: str) -> pd.DataFrame:
    q = f"""
    SELECT
      {_mtype_expr()} AS mtype,
      toInt64(floor(seconds_to_end / 300)) * 300 AS bucket_start_s,
      count() AS n
    FROM {view}
    WHERE username = '{username}'
      AND seconds_to_end IS NOT NULL
      AND seconds_to_end >= 0
      {where_time}
    GROUP BY mtype, bucket_start_s
    ORDER BY mtype, bucket_start_s
    """
    return client.query_df(q)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", default=os.getenv("POLYMARKET_TARGET_USER", "TARGET_USER"))
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    args = ap.parse_args()

    client = clickhouse_connect.get_client(host="localhost", port=8123, database="polybot")
    choice = _pick_enriched_view(client)
    where_time = _time_where(args.start_ts, args.end_ts)

    print(f"Using view: {choice.enriched_view} (exec={choice.exec_col})")

    b_trades, b_resolved, b_notional = fetch_overview(client, choice.enriched_view, args.baseline, where_time)
    c_trades, c_resolved, c_notional = fetch_overview(client, choice.enriched_view, args.candidate, where_time)

    print("\n=== OVERVIEW ===")
    print(f"Baseline  {args.baseline}: trades={b_trades:,} resolved={b_resolved:,} notional=${b_notional:,.2f}")
    print(f"Candidate {args.candidate}: trades={c_trades:,} resolved={c_resolved:,} notional=${c_notional:,.2f}")

    # Market mix
    b_mtype = fetch_distribution(client, choice.enriched_view, args.baseline, where_time, sql_key=_mtype_expr(), col="mtype")
    c_mtype = fetch_distribution(client, choice.enriched_view, args.candidate, where_time, sql_key=_mtype_expr(), col="mtype")
    b_m = _normalize_counts(b_mtype, "mtype")
    c_m = _normalize_counts(c_mtype, "mtype")
    mtype_l1 = _l1_distance(b_m, c_m)

    # Outcome mix
    b_outcome = fetch_distribution(client, choice.enriched_view, args.baseline, where_time, sql_key="lower(outcome)", col="outcome")
    c_outcome = fetch_distribution(client, choice.enriched_view, args.candidate, where_time, sql_key="lower(outcome)", col="outcome")
    outcome_l1 = _l1_distance(_normalize_counts(b_outcome, "outcome"), _normalize_counts(c_outcome, "outcome"))

    # Exec mix
    b_exec = fetch_distribution(client, choice.enriched_view, args.baseline, where_time, sql_key=choice.exec_col, col="exec")
    c_exec = fetch_distribution(client, choice.enriched_view, args.candidate, where_time, sql_key=choice.exec_col, col="exec")
    exec_l1 = _l1_distance(_normalize_counts(b_exec, "exec"), _normalize_counts(c_exec, "exec"))

    # Size distribution (top sizes)
    b_size = fetch_distribution(client, choice.enriched_view, args.baseline, where_time, sql_key="toString(size)", col="size")
    c_size = fetch_distribution(client, choice.enriched_view, args.candidate, where_time, sql_key="toString(size)", col="size")
    b_size = b_size.head(15)
    c_size = c_size.head(15)
    size_l1 = _l1_distance(_normalize_counts(b_size, "size"), _normalize_counts(c_size, "size"))

    # Timing buckets
    b_timing = fetch_timing_buckets(client, choice.enriched_view, args.baseline, where_time)
    c_timing = fetch_timing_buckets(client, choice.enriched_view, args.candidate, where_time)
    if not b_timing.empty and not c_timing.empty:
        b_tb = _normalize_counts(b_timing.groupby(["mtype", "bucket_start_s"], as_index=False)["n"].sum(), "mtype")
        c_tb = _normalize_counts(c_timing.groupby(["mtype", "bucket_start_s"], as_index=False)["n"].sum(), "mtype")
    else:
        b_tb, c_tb = {}, {}
    timing_l1 = _l1_distance(b_tb, c_tb)

    # Score: average the L1 distances (0..2). Convert to 0..100 where higher is closer.
    components = [mtype_l1, outcome_l1, exec_l1, size_l1]
    avg_l1 = sum(components) / len(components)
    score = max(0.0, 100.0 * (1.0 - avg_l1 / 2.0))

    print("\n=== DISTRIBUTION MATCH (lower is better) ===")
    print(f"market mix L1:   {mtype_l1:.3f}")
    print(f"outcome mix L1:  {outcome_l1:.3f}")
    print(f"exec mix L1:     {exec_l1:.3f}")
    print(f"size mix L1:     {size_l1:.3f}")
    print(f"\nReplication score (heuristic): {score:.1f}/100")

    print("\n=== MARKET MIX (baseline) ===")
    print(b_mtype.to_string(index=False))
    print("\n=== MARKET MIX (candidate) ===")
    print(c_mtype.to_string(index=False))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
