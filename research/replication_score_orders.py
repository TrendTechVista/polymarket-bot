#!/usr/bin/env python3
"""
Order-stream replication score for the live/paper gabagool clone.

Goal
----
We want to compare *our bot's decision stream* (strategy order events) to the best-available
gabagool22-inferred distributions. This is not a proof of "exact cloning" (gabagool's cancels/
re-quotes are unobservable), but it is a high-signal calibration loop:

- Cadence: how frequently we act (PLACE events) vs gabagool's fill cadence (trade prints)
- Replace rate: our REPLACE frequency vs a lower-bound "reprice proxy" from gabagool fills
- Top-up latency: our FAST_TOP_UP/TOP_UP behavior vs gabagool lead→lag opposite-fill delays
- Imbalance: our Up/Down filled size imbalance per market instance vs gabagool per condition

Inputs (ClickHouse)
-------------------
- polybot.strategy_gabagool_orders          (our bot decision stream; from strategy events)
- polybot.executor_order_status             (order-status polling; to infer fills)
- polybot.user_trade_enriched_v3/v4/...     (gabagool trade prints; baseline)

Usage
-----
cd research
python3 replication_score_orders.py --hours 24
python3 replication_score_orders.py --start-ts '2025-12-20 00:00:00' --end-ts '2025-12-20 06:00:00'
python3 replication_score_orders.py --hours 6 --run-id <your-run-id>
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from io import StringIO
import os
import sys
from typing import Dict, Iterable, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import numpy as np
import pandas as pd


SERIES_SQL = """multiIf(
  market_slug LIKE 'btc-updown-15m-%', 'btc-15m',
  market_slug LIKE 'eth-updown-15m-%', 'eth-15m',
  market_slug LIKE 'bitcoin-up-or-down-%', 'btc-1h',
  market_slug LIKE 'ethereum-up-or-down-%', 'eth-1h',
  'other'
)"""

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

    def show_tables(self) -> set[str]:
        sql = "SELECT name FROM system.tables WHERE database = currentDatabase() FORMAT TabSeparated"
        text = self._post(sql)
        out: set[str] = set()
        for line in text.splitlines():
            name = line.strip()
            if name:
                out.add(name)
        return out


def _get_client() -> ClickHouseHttp:
    url = os.getenv("CLICKHOUSE_URL")
    if not url:
        host = os.getenv("CLICKHOUSE_HOST", "127.0.0.1")
        port = os.getenv("CLICKHOUSE_PORT", "8123")
        url = f"http://{host}:{port}"
    database = os.getenv("CLICKHOUSE_DATABASE", "polybot")
    user = os.getenv("CLICKHOUSE_USER", "intellij")
    password = os.getenv("CLICKHOUSE_PASSWORD", "")
    timeout_s = int(os.getenv("CLICKHOUSE_TIMEOUT_SECONDS", "30"))
    return ClickHouseHttp(url=url, database=database, user=user, password=password, timeout_seconds=timeout_s)


def _time_where(col: str, start_ts: Optional[str], end_ts: Optional[str], hours: int) -> str:
    if start_ts or end_ts:
        parts = []
        if start_ts:
            parts.append(f"{col} >= parseDateTime64BestEffort('{start_ts}')")
        if end_ts:
            parts.append(f"{col} < parseDateTime64BestEffort('{end_ts}')")
        return " AND " + " AND ".join(parts) if parts else ""
    return f" AND {col} >= now() - INTERVAL {int(hours)} HOUR"


def _l1(p: Dict[str, float], q: Dict[str, float]) -> float:
    keys = set(p) | set(q)
    return float(sum(abs(p.get(k, 0.0) - q.get(k, 0.0)) for k in keys))


def _normalize_counts(counts: Dict[str, int]) -> Dict[str, float]:
    total = float(sum(counts.values()))
    if total <= 0:
        return {}
    return {k: float(v) / total for k, v in counts.items()}


def _bucketize(values_s: Iterable[float], edges: Iterable[float]) -> Dict[str, int]:
    edges = list(edges)
    if len(edges) < 2:
        raise ValueError("Need at least 2 edges")
    labels: list[str] = []
    for i in range(len(edges) - 1):
        a, b = edges[i], edges[i + 1]
        labels.append(f"[{a},{b})" if i < len(edges) - 2 else f"[{a},{b}]")
    labels.append(f">={edges[-1]}")

    out: Dict[str, int] = {lab: 0 for lab in labels}
    for v in values_s:
        if v is None or (isinstance(v, float) and np.isnan(v)):
            continue
        placed = False
        for i in range(len(edges) - 1):
            a, b = edges[i], edges[i + 1]
            is_last = i == len(edges) - 2
            if (v >= a and (v < b or (is_last and v <= b))):
                out[labels[i]] += 1
                placed = True
                break
        if not placed and v >= edges[-1]:
            out[labels[-1]] += 1
    return out


def _nearest_opposite_delta_seconds(times_a: np.ndarray, times_b: np.ndarray) -> np.ndarray:
    if times_a.size == 0 or times_b.size == 0:
        return np.array([], dtype="float64")

    times_a = np.asarray(times_a)
    times_b = np.asarray(times_b)

    j = np.searchsorted(times_b, times_a)
    prev_valid = j > 0
    next_valid = j < times_b.size

    prev_idx = np.where(prev_valid, j - 1, 0)
    next_idx = np.where(next_valid, j, times_b.size - 1)

    prev_t = times_b[prev_idx]
    next_t = times_b[next_idx]

    delta_prev_ns = (prev_t - times_a).astype("timedelta64[ns]").astype("int64")
    delta_next_ns = (next_t - times_a).astype("timedelta64[ns]").astype("int64")

    # Choose nearest available (prefer smaller absolute).
    choose_prev = np.abs(delta_prev_ns) <= np.abs(delta_next_ns)
    delta_ns = np.where(~prev_valid, delta_next_ns, np.where(~next_valid, delta_prev_ns, np.where(choose_prev, delta_prev_ns, delta_next_ns)))
    return delta_ns / 1e9


@dataclass(frozen=True)
class TradeSource:
    table: str


def _pick_trade_source(client) -> TradeSource:
    tables = client.show_tables()
    for t in ("user_trade_enriched_v4", "user_trade_enriched_v3", "user_trade_enriched_v2", "user_trade_research"):
        if t in tables:
            return TradeSource(t)
    raise RuntimeError("No trade source found (expected user_trade_enriched_v2/v3/v4 or user_trade_research)")


def fetch_gabagool_trades(client, source: TradeSource, username: str, where_time: str) -> pd.DataFrame:
    q = f"""
    SELECT
      ts,
      {SERIES_SQL} AS series,
      market_slug,
      token_id,
      condition_id,
      outcome,
      price,
      size
    FROM polybot.{source.table}
    WHERE username = '{username}'
      AND ({SERIES_SQL}) != 'other'
      {where_time}
    ORDER BY ts
    """
    return client.query_df(q)


def fetch_strategy_orders(client, run_id: Optional[str], where_time: str) -> pd.DataFrame:
    run_where = f" AND run_id = '{run_id}'" if run_id else ""
    q = f"""
    SELECT
      ts,
      {SERIES_SQL} AS series,
      action,
      reason,
      market_slug,
      market_type,
      token_id,
      direction,
      success,
      order_id,
      price,
      size,
      replaced_order_age_millis,
      order_age_millis
    FROM polybot.strategy_gabagool_orders
    WHERE 1 = 1
      AND market_slug != ''
      {run_where}
      {where_time}
    ORDER BY ts
    """
    return client.query_df(q)


def fetch_filled_orders(client, where_time: str) -> pd.DataFrame:
    q = f"""
    SELECT
      order_id,
      minIf(ts, matched_size > 0) AS first_fill_ts,
      max(matched_size) AS filled_size,
      argMax(exchange_status, ts) AS last_status,
      argMax(requested_price, ts) AS requested_price
    FROM polybot.executor_order_status
    WHERE order_id != ''
      {where_time}
    GROUP BY order_id
    HAVING filled_size > 0
    """
    return client.query_df(q)


def _compute_interarrival_seconds(df: pd.DataFrame, group_cols: list[str], ts_col: str = "ts") -> np.ndarray:
    if df.empty:
        return np.array([], dtype="float64")
    out: list[float] = []
    for _, g in df.sort_values(ts_col).groupby(group_cols, dropna=True):
        t = pd.to_datetime(g[ts_col], utc=True)
        if len(t) < 2:
            continue
        d = t.diff().dt.total_seconds().to_numpy()
        out.extend([float(x) for x in d[1:] if pd.notna(x) and x >= 0])
    return np.asarray(out, dtype="float64")


def _compute_reprice_proxy_rate(gabagool: pd.DataFrame) -> float:
    if gabagool.empty:
        return float("nan")
    df = gabagool.sort_values("ts")
    # Proxy: within a token, how often does the *fill price* change from the previous fill?
    # This is a lower bound on "replace/reprice" behavior (unfilled cancels are unobservable).
    changed = 0
    total = 0
    for _, g in df.groupby(["token_id"], dropna=True):
        g = g.sort_values("ts")
        p = g["price"].to_numpy()
        if p.size < 2:
            continue
        total += int(p.size - 1)
        changed += int(np.sum(p[1:] != p[:-1]))
    return (float(changed) / float(total)) if total > 0 else float("nan")


def _compute_fill_pair_metrics(trades: pd.DataFrame, *, condition_col: str, outcome_col: str, ts_col: str) -> Tuple[np.ndarray, np.ndarray]:
    """
    Returns (nearest_abs_delay_s, nearest_signed_delay_s) per trade.
    """
    if trades.empty:
        return np.array([], dtype="float64"), np.array([], dtype="float64")

    nearest_signed: list[float] = []
    nearest_abs: list[float] = []

    trades = trades[[condition_col, outcome_col, ts_col]].copy()
    trades[ts_col] = pd.to_datetime(trades[ts_col], utc=True)

    for _, g in trades.groupby(condition_col, dropna=True):
        up = g[g[outcome_col] == "Up"][ts_col].to_numpy(dtype="datetime64[ns]")
        down = g[g[outcome_col] == "Down"][ts_col].to_numpy(dtype="datetime64[ns]")
        if up.size == 0 or down.size == 0:
            continue

        up.sort()
        down.sort()

        d_up = _nearest_opposite_delta_seconds(up, down)
        d_dn = _nearest_opposite_delta_seconds(down, up)

        if d_up.size:
            nearest_signed.extend(d_up.tolist())
            nearest_abs.extend(np.abs(d_up).tolist())
        if d_dn.size:
            nearest_signed.extend(d_dn.tolist())
            nearest_abs.extend(np.abs(d_dn).tolist())

    return np.asarray(nearest_abs, dtype="float64"), np.asarray(nearest_signed, dtype="float64")


def _imbalance_frac(up: float, down: float) -> float:
    denom = up + down
    if denom <= 0:
        return float("nan")
    return abs(up - down) / denom


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-username", default="gabagool22")
    ap.add_argument("--run-id", default=None, help="Optional polybot strategy run_id filter")
    ap.add_argument("--hours", type=int, default=24, help="Default lookback window (used if start/end not provided)")
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    args = ap.parse_args()

    client = _get_client()
    trade_source = _pick_trade_source(client)

    where_trades = _time_where("ts", args.start_ts, args.end_ts, args.hours)
    where_orders = _time_where("ts", args.start_ts, args.end_ts, args.hours)
    where_status = _time_where("ts", args.start_ts, args.end_ts, args.hours)

    try:
        gab = fetch_gabagool_trades(client, trade_source, args.baseline_username, where_trades)
        orders = fetch_strategy_orders(client, args.run_id, where_orders)
        fills = fetch_filled_orders(client, where_status)
    except Exception as e:
        print(f"ClickHouse query failed: {e}", file=sys.stderr)
        return 2

    print(f"Trade source: polybot.{trade_source.table}")
    print(f"Baseline trades: {len(gab):,} | Strategy order events: {len(orders):,} | Filled orders: {len(fills):,}")

    if gab.empty or orders.empty:
        print("Not enough data to score (need both baseline trades and strategy order events in the selected window).")
        return 2

    # -----------------------------------------------------------------------------
    # 1) Cadence: baseline fill cadence vs our PLACE cadence (proxy; not directly comparable)
    # -----------------------------------------------------------------------------
    place = orders[(orders["action"] == "PLACE") & (orders["success"] == 1)].copy()
    place = place[place["series"] != "other"]

    gab_inter_s = _compute_interarrival_seconds(gab, ["series"], "ts")
    place_inter_s = _compute_interarrival_seconds(place, ["series"], "ts")

    cadence_bins = [0, 1, 2, 5, 10, 30, 60, 120]
    gab_cad = _normalize_counts(_bucketize(gab_inter_s, cadence_bins))
    bot_cad = _normalize_counts(_bucketize(place_inter_s, cadence_bins))
    l1_cadence = _l1(gab_cad, bot_cad)

    # -----------------------------------------------------------------------------
    # 2) Replace rate: our REPLACE vs gabagool reprice-proxy (lower bound)
    # -----------------------------------------------------------------------------
    bot_replace_rate = float((place["reason"] == "REPLACE").mean()) if len(place) else float("nan")
    gab_reprice_proxy = _compute_reprice_proxy_rate(gab)
    l1_replace = 2.0 * abs(bot_replace_rate - gab_reprice_proxy) if np.isfinite(bot_replace_rate) and np.isfinite(gab_reprice_proxy) else float("nan")

    # -----------------------------------------------------------------------------
    # 3) Lead→lag fill latency (paired UP/DOWN execution timing)
    #
    # Baseline: gabagool trade prints (condition_id groups).
    # Bot: our filled orders (market_slug groups) using first_fill_ts per order.
    # -----------------------------------------------------------------------------
    gab_abs, gab_signed = _compute_fill_pair_metrics(gab, condition_col="condition_id", outcome_col="outcome", ts_col="ts")
    gab_lead_to_lag = gab_signed[gab_signed > 0]

    # Map order_id -> market_slug/direction for fills.
    oid_map = orders[(orders["order_id"] != "") & (orders["order_id"].notna())][["order_id", "market_slug", "direction", "series"]].copy()
    oid_map = oid_map.drop_duplicates(subset=["order_id"], keep="last")
    fills = fills.merge(oid_map, left_on="order_id", right_on="order_id", how="inner")
    fills["first_fill_ts"] = pd.to_datetime(fills["first_fill_ts"], utc=True)

    bot_trades = fills[["market_slug", "direction", "first_fill_ts"]].copy()
    bot_trades = bot_trades.dropna(subset=["market_slug", "direction", "first_fill_ts"])
    bot_trades = bot_trades.rename(columns={"first_fill_ts": "ts"})
    bot_trades["outcome"] = bot_trades["direction"].astype(str).str.upper().map({"UP": "Up", "DOWN": "Down"})
    bot_trades = bot_trades.dropna(subset=["outcome"])

    bot_abs, bot_signed = _compute_fill_pair_metrics(bot_trades, condition_col="market_slug", outcome_col="outcome", ts_col="ts")
    bot_lead_to_lag = bot_signed[bot_signed > 0]

    latency_bins = [0, 2, 5, 10, 30, 60, 120]
    gab_lat = _normalize_counts(_bucketize(gab_lead_to_lag, latency_bins))
    bot_lat = _normalize_counts(_bucketize(bot_lead_to_lag, latency_bins))
    l1_latency = _l1(gab_lat, bot_lat) if gab_lat and bot_lat else float("nan")

    # -----------------------------------------------------------------------------
    # 4) Imbalance: per-condition vs per-market filled up/down totals
    # -----------------------------------------------------------------------------
    g_tot = gab.groupby(["condition_id", "outcome"], dropna=True)["size"].sum().unstack(fill_value=0)
    g_tot["abs_imb_frac"] = g_tot.apply(lambda r: _imbalance_frac(float(r.get("Up", 0.0)), float(r.get("Down", 0.0))), axis=1)

    # Filled sizes per market slug (from executor fills).
    bot_mkt = fills.groupby(["market_slug", "direction"], dropna=True)["filled_size"].sum().unstack(fill_value=0)
    bot_mkt["abs_imb_frac"] = bot_mkt.apply(lambda r: _imbalance_frac(float(r.get("UP", 0.0)), float(r.get("DOWN", 0.0))), axis=1)

    imb_bins = [0.0, 0.1, 0.2, 0.4, 0.6, 0.8]
    gab_imb = _normalize_counts(_bucketize(g_tot["abs_imb_frac"].to_numpy(), imb_bins))
    bot_imb = _normalize_counts(_bucketize(bot_mkt["abs_imb_frac"].to_numpy(), imb_bins))
    l1_imb = _l1(gab_imb, bot_imb) if gab_imb and bot_imb else float("nan")

    # -----------------------------------------------------------------------------
    # Score aggregation
    # -----------------------------------------------------------------------------
    l1_components = {
        "cadence_l1": l1_cadence,
        "replace_l1": l1_replace,
        "topup_latency_l1": l1_latency,
        "imbalance_l1": l1_imb,
    }
    finite = [v for v in l1_components.values() if np.isfinite(v)]
    if not finite:
        print("No comparable components available in this window.")
        return 2

    avg_l1 = float(sum(finite) / len(finite))
    score = max(0.0, 100.0 * (1.0 - avg_l1 / 2.0))

    print("\n=== ORDER-STREAM REPLICATION SCORE (heuristic) ===")
    print(f"score: {score:.1f}/100 (avg L1={avg_l1:.3f} over {len(finite)} components)")

    print("\n=== COMPONENTS ===")
    print(f"cadence (fill vs place) L1:     {l1_cadence:.3f}")
    if np.isfinite(l1_replace):
        print(f"replace rate (proxy) L1:        {l1_replace:.3f}  (bot={bot_replace_rate:.3f} vs base-proxy={gab_reprice_proxy:.3f})")
    else:
        print(f"replace rate (proxy) L1:        n/a  (bot={bot_replace_rate:.3f} vs base-proxy={gab_reprice_proxy:.3f})")
    print(f"lead→lag fill latency L1:       {l1_latency if np.isfinite(l1_latency) else float('nan'):.3f}  (n={len(bot_lead_to_lag):,} bot paired fills)")
    print(f"imbalance frac (condition) L1:  {l1_imb if np.isfinite(l1_imb) else float('nan'):.3f}  (n={len(bot_mkt):,} bot markets)")

    print("\n=== DISTRIBUTIONS (baseline vs bot) ===")
    print("cadence bins:", cadence_bins)
    print("baseline:", gab_cad)
    print("bot:", bot_cad)

    print("\nlatency bins:", latency_bins)
    print("baseline:", gab_lat)
    print("bot:", bot_lat)

    print("\nimbalance bins:", imb_bins)
    print("baseline:", gab_imb)
    print("bot:", bot_imb)

    print("\nNotes:")
    print("- Replace-rate baseline is a LOWER-BOUND proxy from fills; gabagool cancellations/requotes are not observable.")
    print("- Cadence compares our PLACE events to gabagool fills; treat it as a sanity signal, not a strict target.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
