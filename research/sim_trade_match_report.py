#!/usr/bin/env python3
"""
Match our *live paper* bot fills to gabagool22 trades in the same time window.

This is the closest thing to "are we cloning him in real time?" because it compares
observed gabagool trade prints to our own bot's execution stream (same markets, same clock).

By default, the "sim" side is derived from our bot's executor polling snapshots
(`polybot.executor_order_status`) joined to the strategy decision stream
(`polybot.strategy_gabagool_orders`). This works even when we're in PAPER mode and
do not produce real Polymarket user trades.

If you are actually ingesting a real on-exchange user account for the bot, you can
switch to `--sim-source user-trades` to compare trade prints directly.

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

    def show_tables(self) -> set[str]:
        text = self._post(
            "SELECT name FROM system.tables WHERE database = currentDatabase() FORMAT TabSeparated"
        )
        out: set[str] = set()
        for line in text.splitlines():
            name = line.strip()
            if name:
                out.add(name)
        return out


def _pick_trade_source(ch: ClickHouseHttp) -> str:
    tables = ch.show_tables()
    for t in ("user_trade_enriched_v4", "user_trade_enriched_v3", "user_trade_enriched_v2"):
        if t in tables:
            return t
    raise RuntimeError("No trade source found (expected user_trade_enriched_v2/v3/v4)")


def _fetch_user_trades(
    ch: ClickHouseHttp,
    *,
    trade_source: str,
    username: str,
    where_time: str,
) -> List[Dict[str, str]]:
    sql = f"""
    SELECT
      ts,
      username,
      market_slug,
      outcome,
      side,
      price,
      size
    FROM polybot.{trade_source}
    WHERE username = '{username}'
      AND {SERIES_WHERE}
      {where_time}
    ORDER BY ts
    """
    return ch.query_rows(sql)


def _fetch_bot_fills_as_trades(
    ch: ClickHouseHttp,
    *,
    where_time: str,
    run_id: Optional[str],
) -> List[Dict[str, str]]:
    """
    Reconstruct a "trade print" stream from executor_order_status by taking
    positive deltas in matched_size per order_id, then mapping order_id to
    market/outcome via strategy_gabagool_orders.
    """
    run_where = f" AND run_id = '{run_id}'" if run_id else ""
    sql = f"""
    WITH
      fills AS (
        SELECT
          ts,
          order_id,
          side,
          requested_price,
          (matched_size - lagInFrame(matched_size, 1, 0) OVER (PARTITION BY order_id ORDER BY ts)) AS delta_size
        FROM polybot.executor_order_status
        WHERE order_id != ''
          AND matched_size IS NOT NULL
          {where_time}
      ),
      mapping AS (
        SELECT
          order_id,
          anyLast(market_slug) AS mapped_market_slug,
          anyLast(direction) AS direction
        FROM polybot.strategy_gabagool_orders sgo
        WHERE order_id != ''
          AND sgo.market_slug != ''
          AND ({SERIES_WHERE})
          {run_where}
          {where_time}
        GROUP BY order_id
      )
    SELECT
      f.ts AS ts,
      'polybot-paper' AS username,
      m.mapped_market_slug AS market_slug,
      if(m.direction = 'UP', 'Up', 'Down') AS outcome,
      f.side AS side,
      f.requested_price AS price,
      f.delta_size AS size
    FROM fills f
    INNER JOIN mapping m USING (order_id)
    WHERE f.delta_size > 0
      AND f.requested_price IS NOT NULL
    ORDER BY ts
    """
    return ch.query_rows(sql)


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


def _series_from_slug(slug: str) -> str:
    s = (slug or "").strip()
    if s.startswith("btc-updown-15m-"):
        return "btc-15m"
    if s.startswith("eth-updown-15m-"):
        return "eth-15m"
    if s.startswith("bitcoin-up-or-down-"):
        return "btc-1h"
    if s.startswith("ethereum-up-or-down-"):
        return "eth-1h"
    return "other"


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
    ap.add_argument(
        "--sim-source",
        choices=["executor", "user-trades"],
        default="executor",
        help="Where to read the 'sim' stream from (default: executor fills).",
    )
    ap.add_argument("--run-id", default=None, help="Optional strategy run_id filter (executor mode only)")
    ap.add_argument("--hours", type=int, default=6)
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    ap.add_argument("--max-delta-ms", type=int, default=1500)
    ap.add_argument("--price-eps", type=float, default=0.0005)
    ap.add_argument("--by-series", action="store_true", help="Print per-series recall/precision breakdown")
    ap.add_argument(
        "--top-markets",
        type=int,
        default=0,
        help="Print top N markets by gab volume with match stats (0 = disable)",
    )
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

    where = _time_where("ts", args.start_ts, args.end_ts, args.hours)
    trade_source = _pick_trade_source(ch)
    try:
        gab_rows = _fetch_user_trades(ch, trade_source=trade_source, username=args.gab_username, where_time=where)
        if args.sim_source == "user-trades":
            sim_rows = _fetch_user_trades(ch, trade_source=trade_source, username=args.sim_username, where_time=where)
        else:
            sim_rows = _fetch_bot_fills_as_trades(ch, where_time=where, run_id=args.run_id)
    except Exception as e:
        print(f"ClickHouse query failed: {e}", file=sys.stderr)
        return 2

    print(f"Window: hours={args.hours} start={args.start_ts or '(auto)'} end={args.end_ts or '(auto)'}")
    print(f"Source: trade_source=polybot.{trade_source} sim_source={args.sim_source}{' run_id=' + args.run_id if args.run_id else ''}")
    print(f"Trades: gab={len(gab_rows):,} sim={len(sim_rows):,}")
    if not gab_rows or not sim_rows:
        print("Not enough trades to match.")
        return 2

    gab_total_by_series: Dict[str, int] = {}
    sim_total_by_series: Dict[str, int] = {}
    gab_total_by_market: Dict[str, int] = {}
    sim_total_by_market: Dict[str, int] = {}
    for r in gab_rows:
        m = str(r.get("market_slug") or "")
        s = _series_from_slug(m)
        gab_total_by_series[s] = gab_total_by_series.get(s, 0) + 1
        gab_total_by_market[m] = gab_total_by_market.get(m, 0) + 1
    for r in sim_rows:
        m = str(r.get("market_slug") or "")
        s = _series_from_slug(m)
        sim_total_by_series[s] = sim_total_by_series.get(s, 0) + 1
        sim_total_by_market[m] = sim_total_by_market.get(m, 0) + 1

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

    matched_g_by_series: Dict[str, int] = {}
    matched_s_by_series: Dict[str, int] = {}
    deltas_by_series: Dict[str, List[float]] = {}
    reasons_by_series: Dict[str, Dict[str, int]] = {}

    matched_g_by_market: Dict[str, int] = {}
    matched_s_by_market: Dict[str, int] = {}
    reasons_by_market: Dict[str, Dict[str, int]] = {}

    keys = set(gab_by) | set(sim_by)
    for k in keys:
        g = gab_by.get(k, [])
        s = sim_by.get(k, [])
        if not g:
            continue
        if not s:
            reasons_total["NO_SIM"] += len(g)
            market = k[0]
            series = _series_from_slug(market)
            reasons_by_series.setdefault(series, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})["NO_SIM"] += len(g)
            reasons_by_market.setdefault(market, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})["NO_SIM"] += len(g)
            continue
        mg, ms, deltas, reasons = _match_one_bucket(g, s, max_delta_ms=args.max_delta_ms, price_eps=args.price_eps)
        matched_g_total += mg
        matched_s_total += ms
        abs_deltas.extend(deltas)

        market = k[0]
        series = _series_from_slug(market)
        matched_g_by_series[series] = matched_g_by_series.get(series, 0) + mg
        matched_s_by_series[series] = matched_s_by_series.get(series, 0) + ms
        deltas_by_series.setdefault(series, []).extend(deltas)
        rb = reasons_by_series.setdefault(series, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})
        for rk, rv in reasons.items():
            rb[rk] = rb.get(rk, 0) + rv

        matched_g_by_market[market] = matched_g_by_market.get(market, 0) + mg
        matched_s_by_market[market] = matched_s_by_market.get(market, 0) + ms
        rm = reasons_by_market.setdefault(market, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})
        for rk, rv in reasons.items():
            rm[rk] = rm.get(rk, 0) + rv

        for rk, rv in reasons.items():
            reasons_total[rk] = reasons_total.get(rk, 0) + rv

    recall = matched_g_total / len(gab_rows) if gab_rows else 0.0
    precision = matched_s_total / len(sim_rows) if sim_rows else 0.0

    print("\n**Strict Match Results**")
    print(f"- recall (gab matched): {matched_g_total:,}/{len(gab_rows):,} = {recall*100:.2f}%")
    print(f"- precision (sim matched): {matched_s_total:,}/{len(sim_rows):,} = {precision*100:.2f}%")
    print(f"- abs time delta ms: median={_median(abs_deltas):.1f} p90={_quantile(abs_deltas, 0.9):.1f} n={len(abs_deltas):,}")
    print(f"- mismatch reasons: {reasons_total}")

    if args.by_series:
        print("\n**By Series**")
        series_all = sorted(set(gab_total_by_series) | set(sim_total_by_series))
        header = "series     gab    sim    gab_match  sim_match  recall%  prec%   median_dt_ms  p90_dt_ms  NO_SIM  NO_PRICE"
        print(header)
        for series in series_all:
            gab_n = gab_total_by_series.get(series, 0)
            sim_n = sim_total_by_series.get(series, 0)
            mg = matched_g_by_series.get(series, 0)
            ms = matched_s_by_series.get(series, 0)
            r = (mg / gab_n * 100.0) if gab_n else 0.0
            p = (ms / sim_n * 100.0) if sim_n else 0.0
            deltas = deltas_by_series.get(series, [])
            rs = reasons_by_series.get(series, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})
            print(
                f"{series:8} {gab_n:6} {sim_n:6} {mg:9} {ms:9} {r:7.2f} {p:6.2f} "
                f"{_median(deltas):12.1f} {_quantile(deltas, 0.9):9.1f} {rs.get('NO_SIM',0):6} {rs.get('NO_PRICE_MATCH',0):8}"
            )

    if args.top_markets and args.top_markets > 0:
        print(f"\n**Top Markets (by gab volume, n={args.top_markets})**")
        markets = sorted(gab_total_by_market.items(), key=lambda kv: kv[1], reverse=True)[: args.top_markets]
        header = "market_slug                                series   gab  sim  gab_match  recall%  NO_SIM  NO_PRICE"
        print(header)
        for market, gab_n in markets:
            series = _series_from_slug(market)
            sim_n = sim_total_by_market.get(market, 0)
            mg = matched_g_by_market.get(market, 0)
            r = (mg / gab_n * 100.0) if gab_n else 0.0
            rm = reasons_by_market.get(market, {"NO_SIM": 0, "NO_PRICE_MATCH": 0, "NO_TIME_MATCH": 0})
            print(
                f"{market:40} {series:7} {gab_n:4} {sim_n:4} {mg:9} {r:7.2f} {rm.get('NO_SIM',0):6} {rm.get('NO_PRICE_MATCH',0):8}"
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
