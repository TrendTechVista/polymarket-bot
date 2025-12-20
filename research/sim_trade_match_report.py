#!/usr/bin/env python3
"""
Match our *live paper* simulated trades to gabagool22 trades in the same time window.

This is the closest thing to "are we cloning him in real time?" because it compares
observed trade prints from both accounts (same markets, same clock).

Matching rule (strict by default)
---------------------------------
A gabagool trade is considered matched if we have an unused sim trade with:
  - same market_slug
  - same outcome
  - same side
  - price within `--price-eps` (default 0.0005 ~ half-tick)
  - timestamp within `--max-delta-ms` (default 1500ms)

Outputs
-------
- recall: matched_gab / total_gab
- precision: matched_sim / total_sim
- median/p90 absolute time delta (ms)
- top mismatch reasons (no sim trade / wrong price / etc.)

Requires ClickHouse (HTTP). Uses only the Python stdlib (no numpy/pandas) so it runs
reliably across arm64/x86_64 setups.
"""

from __future__ import annotations

import argparse
import csv
import os
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from io import StringIO
from typing import Dict, List, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SERIES_WHERE = (
    "(market_slug LIKE 'btc-updown-15m-%' OR market_slug LIKE 'eth-updown-15m-%' "
    " OR market_slug LIKE 'bitcoin-up-or-down-%' OR market_slug LIKE 'ethereum-up-or-down-%')"
)


def _parse_dt64(s: str) -> datetime:
    # ClickHouse DateTime64 string examples:
    # 2025-12-20 13:02:24.351
    # 2025-12-20 13:02:24
    s = s.strip()
    if not s:
        raise ValueError("empty timestamp")
    if "T" in s:
        # ISO-ish
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        return dt.astimezone(timezone.utc)
    # Space separated:
    if "." in s:
        base, frac = s.split(".", 1)
        dt = datetime.strptime(base, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        ms = int((frac + "000")[:3])
        return dt.replace(microsecond=ms * 1000)
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)


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

    def query_rows(self, sql: str) -> List[Dict[str, str]]:
        sql = sql.strip().rstrip(";") + "\nFORMAT CSVWithNames"
        text = self._post(sql)
        if not text.strip():
            return []
        reader = csv.DictReader(StringIO(text))
        return [dict(r) for r in reader]


def _time_where(col: str, start_ts: Optional[str], end_ts: Optional[str], hours: int) -> str:
    if start_ts or end_ts:
        parts = []
        if start_ts:
            parts.append(f"{col} >= parseDateTime64BestEffort('{start_ts}')")
        if end_ts:
            parts.append(f"{col} < parseDateTime64BestEffort('{end_ts}')")
        return " AND " + " AND ".join(parts) if parts else ""
    return f" AND {col} >= now() - INTERVAL {int(hours)} HOUR"


def _median(values: List[float]) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    n = len(s)
    mid = n // 2
    if n % 2 == 1:
        return float(s[mid])
    return float((s[mid - 1] + s[mid]) / 2.0)


def _quantile(values: List[float], q: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    idx = int(round((len(s) - 1) * q))
    idx = max(0, min(len(s) - 1, idx))
    return float(s[idx])


def _match_one_bucket(
    gab: List[Dict[str, str]],
    sim: List[Dict[str, str]],
    *,
    max_delta_ms: int,
    price_eps: float,
) -> Tuple[int, int, List[float], Dict[str, int]]:
    """
    Two-pointer greedy match within a single key bucket (market/outcome/side).
    Returns: (matched_gab, matched_sim, abs_deltas_ms, reasons)
    """
    gab_sorted = sorted(gab, key=lambda r: r["ts"])
    sim_sorted = sorted(sim, key=lambda r: r["ts"])

    matched_sim = [False] * len(sim_sorted)
    abs_deltas: List[float] = []
    reasons: Dict[str, int] = {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0}

    j = 0
    matched_g = 0
    matched_s = 0

    for g in gab_sorted:
        g_ts = _parse_dt64(g["ts"])
        g_ms = int(g_ts.timestamp() * 1000)
        g_price = float(g["price"])

        # Advance sim pointer to within lower time bound.
        while j < len(sim_sorted):
            s_ts = _parse_dt64(sim_sorted[j]["ts"])
            s_ms = int(s_ts.timestamp() * 1000)
            if s_ms < g_ms - max_delta_ms:
                j += 1
                continue
            break

        # Scan forward from j while within upper bound.
        best_idx = None
        best_delta = None
        best_price_diff = None
        scanned_any = False
        saw_time = False
        saw_price = False

        k = j
        while k < len(sim_sorted):
            if matched_sim[k]:
                k += 1
                continue
            s_ts = _parse_dt64(sim_sorted[k]["ts"])
            s_ms = int(s_ts.timestamp() * 1000)
            delta = s_ms - g_ms
            if delta > max_delta_ms:
                break
            scanned_any = True
            saw_time = True

            s_price = float(sim_sorted[k]["price"])
            price_diff = abs(s_price - g_price)
            if price_diff <= price_eps:
                saw_price = True
                abs_delta = abs(delta)
                if best_delta is None or abs_delta < best_delta or (abs_delta == best_delta and price_diff < (best_price_diff or 1e9)):
                    best_idx = k
                    best_delta = abs_delta
                    best_price_diff = price_diff
            k += 1

        if best_idx is not None:
            matched_sim[best_idx] = True
            matched_g += 1
            matched_s += 1
            abs_deltas.append(float(best_delta or 0))
        else:
            if not scanned_any:
                reasons["NO_SIM"] += 1
            elif saw_time and not saw_price:
                reasons["NO_PRICE_MATCH"] += 1
            else:
                reasons["NO_TIME_MATCH"] += 1

    return matched_g, matched_s, abs_deltas, reasons


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gab-username", default="gabagool22")
    ap.add_argument("--sim-username", default="polybot-sim")
    ap.add_argument("--hours", type=int, default=6)
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    ap.add_argument("--max-delta-ms", type=int, default=1500)
    ap.add_argument("--price-eps", type=float, default=0.0005)
    args = ap.parse_args()

    ch = ClickHouseHttp(
        url=os.getenv("CLICKHOUSE_URL", "http://localhost:8123"),
        database=os.getenv("CLICKHOUSE_DATABASE", "polybot"),
        user=os.getenv("CLICKHOUSE_USER", "default"),
        password=os.getenv("CLICKHOUSE_PASSWORD", ""),
        timeout_seconds=int(os.getenv("CLICKHOUSE_TIMEOUT_SECONDS", "30")),
    )

    where = _time_where("ts", args.start_ts, args.end_ts, args.hours)
    sql = f"""
    SELECT
      ts,
      username,
      market_slug,
      outcome,
      side,
      price,
      size
    FROM polybot.user_trade_enriched_v4
    WHERE username IN ('{args.gab_username}', '{args.sim_username}')
      AND {SERIES_WHERE}
      {where}
    ORDER BY ts
    """

    try:
        rows = ch.query_rows(sql)
    except Exception as e:
        print(f"ClickHouse query failed: {e}", file=sys.stderr)
        return 2

    gab_rows = [r for r in rows if r.get("username") == args.gab_username]
    sim_rows = [r for r in rows if r.get("username") == args.sim_username]

    print(f"Window: hours={args.hours} start={args.start_ts or '(auto)'} end={args.end_ts or '(auto)'}")
    print(f"Trades: gab={len(gab_rows):,} sim={len(sim_rows):,}")
    if not gab_rows or not sim_rows:
        print("Not enough trades to match.")
        return 2

    # Bucket by (market_slug, outcome, side).
    gab_by: Dict[Tuple[str, str, str], List[Dict[str, str]]] = {}
    sim_by: Dict[Tuple[str, str, str], List[Dict[str, str]]] = {}

    def key(r: Dict[str, str]) -> Tuple[str, str, str]:
        return (str(r.get("market_slug") or ""), str(r.get("outcome") or ""), str(r.get("side") or ""))

    for r in gab_rows:
        gab_by.setdefault(key(r), []).append(r)
    for r in sim_rows:
        sim_by.setdefault(key(r), []).append(r)

    matched_g_total = 0
    matched_s_total = 0
    abs_deltas: List[float] = []
    reasons_total: Dict[str, int] = {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0}

    keys = set(gab_by) | set(sim_by)
    for k in keys:
        g = gab_by.get(k, [])
        s = sim_by.get(k, [])
        if not g:
            continue
        if not s:
            reasons_total["NO_SIM"] += len(g)
            continue
        mg, ms, deltas, reasons = _match_one_bucket(g, s, max_delta_ms=args.max_delta_ms, price_eps=args.price_eps)
        matched_g_total += mg
        matched_s_total += ms
        abs_deltas.extend(deltas)
        for rk, rv in reasons.items():
            reasons_total[rk] = reasons_total.get(rk, 0) + rv

    recall = matched_g_total / len(gab_rows) if gab_rows else 0.0
    precision = matched_s_total / len(sim_rows) if sim_rows else 0.0

    print("\n**Strict Match Results**")
    print(f"- recall (gab matched): {matched_g_total:,}/{len(gab_rows):,} = {recall*100:.2f}%")
    print(f"- precision (sim matched): {matched_s_total:,}/{len(sim_rows):,} = {precision*100:.2f}%")
    print(f"- abs time delta ms: median={_median(abs_deltas):.1f} p90={_quantile(abs_deltas, 0.9):.1f} n={len(abs_deltas):,}")
    print(f"- mismatch reasons: {reasons_total}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

