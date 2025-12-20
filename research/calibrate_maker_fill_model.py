#!/usr/bin/env python3
"""
Calibrate the paper maker fill/queue model from our own executor snapshots.

This script estimates:
  - maker-fill-probability-per-poll (base p at best bid)
  - maker-fill-probability-multiplier-per-tick (queue priority proxy)
  - maker-fill-fraction-of-remaining (from first-fill fraction)

Data sources (ClickHouse):
  - polybot.strategy_gabagool_orders
  - polybot.executor_order_status

Notes:
  - We only calibrate MAKER-like orders (BUY limit < bestAsk at placement).
  - The estimated p is derived from the *median* first-fill latency under a geometric model.
  - This is meant to close the gap between simulated vs real execution timing.
"""

from __future__ import annotations

import argparse
import math
import os
import sys
from dataclasses import dataclass
from io import StringIO
from typing import Optional
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pandas as pd


def _has_format(sql: str) -> bool:
    return " format " in f" {sql.lower()} "


@dataclass(frozen=True)
class ClickHouseHttp:
    url: str
    database: str
    user: str
    password: str
    timeout_seconds: int

    def _post(self, sql: str) -> str:
        params = {"database": self.database}
        if self.user:
            params["user"] = self.user
        if self.password:
            params["password"] = self.password
        full = f"{self.url.rstrip('/')}/?{urlencode(params)}"
        req = Request(full, data=sql.encode("utf-8"), method="POST")
        with urlopen(req, timeout=self.timeout_seconds) as resp:
            return resp.read().decode("utf-8")

    def query_df(self, sql: str) -> pd.DataFrame:
        sql = sql.strip().rstrip(";")
        if not _has_format(sql):
            sql = sql + "\nFORMAT CSVWithNames"
        text = self._post(sql)
        if not text.strip():
            return pd.DataFrame()
        return pd.read_csv(StringIO(text))


def _time_where(col: str, start_ts: Optional[str], end_ts: Optional[str], hours: int) -> str:
    if start_ts or end_ts:
        parts = []
        if start_ts:
            parts.append(f"{col} >= parseDateTime64BestEffort('{start_ts}')")
        if end_ts:
            parts.append(f"{col} < parseDateTime64BestEffort('{end_ts}')")
        return " AND " + " AND ".join(parts) if parts else ""
    return f" AND {col} >= now() - INTERVAL {int(hours)} HOUR"


def _geometric_p_from_median_latency(latency_ms: float, poll_ms: int) -> float:
    if latency_ms <= 0 or poll_ms <= 0:
        return 1.0
    n = max(1, int(round(latency_ms / poll_ms)))
    # median: (1-p)^n = 0.5 => p = 1 - 0.5^(1/n)
    return float(1.0 - math.pow(0.5, 1.0 / float(n)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-id", default=None, help="Optional strategy run_id filter")
    ap.add_argument("--hours", type=int, default=24)
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    ap.add_argument("--fill-poll-millis", type=int, default=250)
    ap.add_argument("--min-samples", type=int, default=50)
    args = ap.parse_args()

    ch = ClickHouseHttp(
        url=(
            os.getenv("CLICKHOUSE_URL")
            or f"http://{os.getenv('CLICKHOUSE_HOST', '127.0.0.1')}:{os.getenv('CLICKHOUSE_PORT', '8123')}"
        ),
        database=os.getenv("CLICKHOUSE_DATABASE", "polybot"),
        user=os.getenv("CLICKHOUSE_USER", "intellij"),
        password=os.getenv("CLICKHOUSE_PASSWORD", ""),
        timeout_seconds=int(os.getenv("CLICKHOUSE_TIMEOUT_SECONDS", "30")),
    )

    where_s = _time_where("ts", args.start_ts, args.end_ts, args.hours)
    run_where = f" AND run_id = '{args.run_id}'" if args.run_id else ""

    df = ch.query_df(
        f"""
        WITH
          placed AS (
            SELECT
              order_id,
              anyLast(run_id) AS run_id,
              anyLast(token_id) AS token_id,
              minIf(ts, action = 'PLACE' AND success = 1) AS placed_ts,
              argMinIf(price, ts, action = 'PLACE' AND success = 1) AS placed_price,
              argMinIf(size, ts, action = 'PLACE' AND success = 1) AS placed_size,
              argMinIf(tick_size, ts, action = 'PLACE' AND success = 1) AS tick_size,
              argMinIf(best_bid_price, ts, action = 'PLACE' AND success = 1) AS best_bid_price_at_place,
              argMinIf(best_ask_price, ts, action = 'PLACE' AND success = 1) AS best_ask_price_at_place
            FROM polybot.strategy_gabagool_orders
            WHERE order_id != ''
              {run_where}
              {where_s}
            GROUP BY order_id
          ),
          fills AS (
            SELECT
              order_id,
              minIf(ts, matched_size > 0) AS first_fill_ts,
              argMinIf(matched_size, ts, matched_size > 0) AS first_matched_size,
              max(requested_size) AS requested_size
            FROM polybot.executor_order_status
            WHERE order_id != ''
              {where_s}
            GROUP BY order_id
            HAVING max(ifNull(matched_size, 0)) > 0
          )
        SELECT
          p.order_id,
          p.token_id,
          p.placed_ts,
          p.placed_price,
          p.placed_size,
          ifNull(p.tick_size, 0.01) AS tick_size,
          p.best_bid_price_at_place,
          p.best_ask_price_at_place,
          f.first_fill_ts,
          f.first_matched_size,
          f.requested_size,
          dateDiff('millisecond', p.placed_ts, f.first_fill_ts) AS fill_latency_ms,
          toInt64(floor((p.placed_price - p.best_bid_price_at_place) / ifNull(p.tick_size, 0.01))) AS ticks_above_best_bid
        FROM placed p
        INNER JOIN fills f USING (order_id)
        WHERE p.placed_ts IS NOT NULL
          AND f.first_fill_ts IS NOT NULL
          AND p.placed_price IS NOT NULL
          AND p.best_bid_price_at_place IS NOT NULL
          AND p.best_ask_price_at_place IS NOT NULL
          AND p.placed_price >= p.best_bid_price_at_place
          AND p.placed_price < p.best_ask_price_at_place
        """
    )

    if df.empty:
        print("No maker fills found for the selected window/run_id.")
        return 2

    df["fill_latency_ms"] = pd.to_numeric(df["fill_latency_ms"], errors="coerce")
    df["ticks_above_best_bid"] = pd.to_numeric(df["ticks_above_best_bid"], errors="coerce")
    df = df.dropna(subset=["fill_latency_ms", "ticks_above_best_bid"])
    df = df[df["fill_latency_ms"] >= 0]
    if df.empty:
        print("No usable rows after cleaning.")
        return 2

    per_k = (
        df.groupby("ticks_above_best_bid")
        .agg(n=("order_id", "count"), median_latency_ms=("fill_latency_ms", "median"))
        .reset_index()
        .sort_values("ticks_above_best_bid")
    )
    per_k = per_k[per_k["n"] >= int(args.min_samples)]
    if per_k.empty:
        print(f"Not enough samples per tick (min-samples={args.min_samples}).")
        print("Raw tick distribution:")
        print(df["ticks_above_best_bid"].value_counts().head(20).to_string())
        return 2

    per_k["p_hat"] = per_k["median_latency_ms"].apply(lambda ms: _geometric_p_from_median_latency(float(ms), args.fill_poll_millis))

    # Fit log(p_hat) ~ a + b*k  => p0 = exp(a), mult = exp(b)
    xs = per_k["ticks_above_best_bid"].astype(float).to_list()
    ys = [math.log(max(1e-9, min(1.0, float(p)))) for p in per_k["p_hat"].to_list()]

    if len(xs) == 1:
        p0 = float(per_k["p_hat"].iloc[0])
        mult = 1.0
    else:
        x_mean = sum(xs) / len(xs)
        y_mean = sum(ys) / len(ys)
        num = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys))
        den = sum((x - x_mean) ** 2 for x in xs) or 1.0
        b = num / den
        a = y_mean - b * x_mean
        p0 = float(math.exp(a))
        mult = float(math.exp(b))

    p0 = max(0.0, min(1.0, p0))
    mult = max(0.0, mult)
    p_max = float(max(per_k["p_hat"].to_list()))

    # First fill fraction proxy.
    df["first_fill_frac"] = pd.to_numeric(df["first_matched_size"], errors="coerce") / pd.to_numeric(df["requested_size"], errors="coerce")
    frac = df["first_fill_frac"].dropna()
    frac = frac[(frac > 0) & (frac <= 1.0)]
    frac_median = float(frac.median()) if not frac.empty else float("nan")

    print("\n**Calibration Summary**")
    print(f"- maker sample orders: {len(df):,}")
    print(f"- tick buckets used (>= {args.min_samples} samples): {len(per_k)}")
    print("\nTick buckets:")
    print(per_k.to_string(index=False))

    print("\nRecommended executor.sim settings:")
    print(f"  maker-fill-probability-per-poll: {p0:.4f}")
    print(f"  maker-fill-probability-multiplier-per-tick: {mult:.4f}")
    print(f"  maker-fill-probability-max-per-poll: {p_max:.4f}")
    if math.isfinite(frac_median):
        print(f"  maker-fill-fraction-of-remaining: {frac_median:.4f}")
    else:
        print("  maker-fill-fraction-of-remaining: (unable to estimate; keep current)")

    print("\nYAML snippet:")
    print("executor:")
    print("  sim:")
    print(f"    maker-fill-probability-per-poll: {p0:.4f}")
    print(f"    maker-fill-probability-multiplier-per-tick: {mult:.4f}")
    print(f"    maker-fill-probability-max-per-poll: {p_max:.4f}")
    if math.isfinite(frac_median):
        print(f"    maker-fill-fraction-of-remaining: {frac_median:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
