#!/usr/bin/env python3
"""
Offline (Parquet) snapshot report for target user reverse-engineering.

Works without ClickHouse: point it at a snapshot directory created by `research/snapshot.py`.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import numpy as np
import pandas as pd


def _read_json(path: Path) -> Optional[dict]:
    if not path.exists():
        return None
    return json.loads(path.read_text())


def _series(slug: str) -> str:
    if slug.startswith("btc-updown-15m-"):
        return "btc-15m"
    if slug.startswith("eth-updown-15m-"):
        return "eth-15m"
    if slug.startswith("bitcoin-up-or-down-"):
        return "btc-1h"
    if slug.startswith("ethereum-up-or-down-"):
        return "eth-1h"
    return "other"


def _bucket_15m(seconds_to_end: int) -> str:
    s = int(seconds_to_end)
    if s < 60:
        return "<1m"
    if s < 180:
        return "1-3m"
    if s < 300:
        return "3-5m"
    if s < 600:
        return "5-10m"
    return "10-15m"


def _bucket_1h(seconds_to_end: int) -> str:
    s = int(seconds_to_end)
    if s < 60:
        return "<1m"
    if s < 180:
        return "1-3m"
    if s < 300:
        return "3-5m"
    if s < 600:
        return "5-10m"
    if s < 900:
        return "10-15m"
    if s < 1200:
        return "15-20m"
    if s < 1800:
        return "20-30m"
    return "30-60m"


def _quantiles(x: np.ndarray, qs: Iterable[float]) -> Dict[float, float]:
    x = x[np.isfinite(x)]
    if x.size == 0:
        return {q: float("nan") for q in qs}
    return {q: float(np.quantile(x, q)) for q in qs}


def _pairing_delays_seconds(trades: pd.DataFrame) -> np.ndarray:
    # For each trade: min abs time to the opposite outcome trade within the same condition.
    x = trades[(trades["side"].str.upper() == "BUY") & trades["outcome"].isin(["Up", "Down"])].copy()
    delays: List[float] = []

    for _, g in x.groupby("condition_id"):
        g = g.sort_values("ts")
        up = g[g["outcome"] == "Up"][["ts"]].copy()
        down = g[g["outcome"] == "Down"][["ts"]].copy()
        if up.empty or down.empty:
            continue

        down2 = down.copy()
        down2["down_ts"] = down2["ts"]
        down2 = down2.sort_values("down_ts")
        up_sorted = up.sort_values("ts")
        back = pd.merge_asof(up_sorted, down2, left_on="ts", right_on="down_ts", direction="backward")
        fwd = pd.merge_asof(up_sorted, down2, left_on="ts", right_on="down_ts", direction="forward")
        for b, f, t in zip(back["down_ts"], fwd["down_ts"], up_sorted["ts"]):
            best = None
            if pd.notna(b):
                best = abs((t - b).total_seconds())
            if pd.notna(f):
                d = abs((t - f).total_seconds())
                best = d if best is None else min(best, d)
            if best is not None:
                delays.append(best)

        up2 = up_sorted[["ts"]].copy()
        up2["up_ts"] = up2["ts"]
        up2 = up2.sort_values("up_ts")
        down_sorted = down.sort_values("ts")
        back2 = pd.merge_asof(down_sorted, up2, left_on="ts", right_on="up_ts", direction="backward")
        fwd2 = pd.merge_asof(down_sorted, up2, left_on="ts", right_on="up_ts", direction="forward")
        for b, f, t in zip(back2["up_ts"], fwd2["up_ts"], down_sorted["ts"]):
            best = None
            if pd.notna(b):
                best = abs((t - b).total_seconds())
            if pd.notna(f):
                d = abs((t - f).total_seconds())
                best = d if best is None else min(best, d)
            if best is not None:
                delays.append(best)

    return np.asarray(delays, dtype=float)


def _edge_from_clob_tob(clob_tob: pd.DataFrame) -> Dict[str, Dict[float, float]]:
    cl = clob_tob[clob_tob["outcome"].isin(["Up", "Down"])].copy()
    if cl.empty:
        return {}

    cl["lag_s"] = (cl["captured_at"] - cl["trade_at"]).dt.total_seconds().abs()
    best = cl.sort_values("lag_s").groupby(["trade_key", "outcome"], as_index=False).first()
    pivot = best.pivot(index="trade_key", columns="outcome")
    both = pivot.dropna(subset=[("best_bid_price", "Up"), ("best_bid_price", "Down")])
    if both.empty:
        return {}

    bid_up = both[("best_bid_price", "Up")].astype(float)
    bid_down = both[("best_bid_price", "Down")].astype(float)
    ask_up = both[("best_ask_price", "Up")].astype(float)
    ask_down = both[("best_ask_price", "Down")].astype(float)

    edges = {
        "edge_bid": 1.0 - (bid_up + bid_down),
        "edge_bid_ask": 1.0 - (bid_up + ask_down),
        "edge_ask_bid": 1.0 - (ask_up + bid_down),
        "edge_ask": 1.0 - (ask_up + ask_down),
    }
    qs = [0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95]
    out: Dict[str, Dict[float, float]] = {}
    for k, v in edges.items():
        arr = v.to_numpy(dtype=float)
        out[k] = _quantiles(arr, qs)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--snapshot", required=True, help="Path to snapshot directory")
    ap.add_argument("--write-md", default=None, help="Optional path to write a Markdown report")
    args = ap.parse_args()

    snap = Path(args.snapshot).expanduser().resolve()
    manifest = _read_json(snap / "manifest.json")

    trades_path = snap / "trades.parquet"
    if not trades_path.exists():
        raise SystemExit(f"Missing {trades_path}")
    trades = pd.read_parquet(trades_path)

    clob_tob_path = snap / "clob_tob.parquet"
    clob_tob = pd.read_parquet(clob_tob_path) if clob_tob_path.exists() else pd.DataFrame()

    total_trades = int(len(trades))
    buy_trades = int((trades["side"].str.upper() == "BUY").sum()) if "side" in trades.columns else 0
    resolved = trades[trades.get("is_resolved", 0) == 1] if "is_resolved" in trades.columns else pd.DataFrame()
    notional = float(trades.get("notional_usd", pd.Series(dtype=float)).fillna(0).sum())
    pnl = float(resolved.get("realized_pnl", pd.Series(dtype=float)).fillna(0).sum()) if not resolved.empty else float("nan")

    series = trades["market_slug"].map(_series) if "market_slug" in trades.columns else pd.Series([], dtype=str)
    series_counts = series.value_counts().to_dict()

    # Outcome balance + realized PnL by outcome (resolved only)
    outcome_lines: List[str] = []
    if "outcome" in trades.columns:
        oc = trades["outcome"].value_counts().to_dict()
        if oc:
            outcome_lines.append(f"- Outcome counts: {', '.join([f'`{k}`={v:,}' for k, v in sorted(oc.items())])}")
    if not resolved.empty and "outcome" in resolved.columns:
        rp = resolved.groupby("outcome")["realized_pnl"].sum().sort_values(ascending=False)
        if not rp.empty:
            outcome_lines.append(
                "- Resolved realized PnL by outcome: "
                + ", ".join([f"`{k}`=${float(v):,.2f}" for k, v in rp.to_dict().items()])
            )
        wr = (
            resolved.assign(_win=(resolved.get("realized_pnl", 0).fillna(0) > 0).astype(int))
            .groupby("outcome")["_win"]
            .mean()
            * 100.0
        )
        if not wr.empty:
            outcome_lines.append(
                "- Resolved win-rate by outcome: "
                + ", ".join([f"`{k}`={float(v):.2f}%" for k, v in wr.to_dict().items()])
            )

    # Pairing delays
    delays = _pairing_delays_seconds(trades)
    delay_q = _quantiles(delays, [0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99])

    # Condition-level pairing coverage
    cond_counts = trades.groupby(["condition_id", "outcome"]).size().unstack(fill_value=0) if "condition_id" in trades.columns else pd.DataFrame()
    cond_total = int(len(cond_counts)) if not cond_counts.empty else 0
    cond_both = int(((cond_counts.get("Up", 0) > 0) & (cond_counts.get("Down", 0) > 0)).sum()) if not cond_counts.empty else 0

    # Condition-level sizing imbalance (how "complete-set-like" in aggregate).
    imbalance_lines: List[str] = []
    if not cond_counts.empty and "size" in trades.columns:
        agg = trades[trades["outcome"].isin(["Up", "Down"])].groupby(["condition_id", "outcome"])["size"].sum().unstack(fill_value=0)
        agg["total"] = agg.get("Up", 0) + agg.get("Down", 0)
        both = agg[(agg.get("Up", 0) > 0) & (agg.get("Down", 0) > 0) & (agg["total"] > 0)].copy()
        if not both.empty:
            frac = ((both["Up"] - both["Down"]) / both["total"]).to_numpy(dtype=float)
            frac_abs = np.abs(frac[np.isfinite(frac)])
            if frac_abs.size:
                q = _quantiles(frac_abs, [0.5, 0.9, 0.95])
                imbalance_lines.append(f"- Abs size imbalance frac `|(Up-Down)/(Up+Down)|`: p50={q[0.5]:.3f} p90={q[0.9]:.3f} p95={q[0.95]:.3f}")
            ratio = (both["Up"] / both["Down"]).to_numpy(dtype=float)
            ratio = ratio[np.isfinite(ratio)]
            if ratio.size:
                q = _quantiles(ratio, [0.5, 0.9, 0.95])
                imbalance_lines.append(f"- Total size ratio `Up/Down`: p50={q[0.5]:.3f} p90={q[0.9]:.3f} p95={q[0.95]:.3f}")

    # Sizing by bucket
    sizing_lines: List[str] = []
    if "seconds_to_end" in trades.columns:
        tmp = trades.copy()
        tmp = tmp[tmp["seconds_to_end"].notna()].copy()
        tmp["series"] = tmp["market_slug"].map(_series)
        tmp["bucket"] = np.where(
            tmp["series"].isin(["btc-15m", "eth-15m"]),
            tmp["seconds_to_end"].astype(int).map(_bucket_15m),
            tmp["seconds_to_end"].astype(int).map(_bucket_1h),
        )
        g = (
            tmp[tmp["series"].isin(["btc-15m", "eth-15m", "btc-1h", "eth-1h"])]
            .groupby(["series", "bucket"])
            .agg(n=("size", "size"), median_size=("size", "median"))
            .reset_index()
            .sort_values(["series", "bucket"])
        )
        for _, row in g.iterrows():
            sizing_lines.append(f"- `{row['series']}` `{row['bucket']}`: n={int(row['n'])} median_size={float(row['median_size']):.2f}")

    # Timing distribution by bucket (within each series).
    timing_lines: List[str] = []
    if "seconds_to_end" in trades.columns and "market_slug" in trades.columns:
        tmp = trades.copy()
        tmp = tmp[tmp["seconds_to_end"].notna()].copy()
        tmp["series"] = tmp["market_slug"].map(_series)
        tmp = tmp[tmp["series"].isin(["btc-15m", "eth-15m", "btc-1h", "eth-1h"])].copy()
        if not tmp.empty:
            tmp["bucket"] = np.where(
                tmp["series"].isin(["btc-15m", "eth-15m"]),
                tmp["seconds_to_end"].astype(int).map(_bucket_15m),
                tmp["seconds_to_end"].astype(int).map(_bucket_1h),
            )
            g = (
                tmp.groupby(["series", "bucket"])
                .size()
                .reset_index(name="n")
            )
            totals = g.groupby("series")["n"].transform("sum")
            g["pct"] = g["n"] / totals * 100.0
            g = g.sort_values(["series", "pct"], ascending=[True, False])
            for series_name, gg in g.groupby("series"):
                top = gg.head(5)
                timing_lines.append(
                    f"- `{series_name}` top buckets: "
                    + ", ".join([f"`{b}`={int(n):,} ({p:.1f}%)" for b, n, p in zip(top["bucket"], top["n"], top["pct"])])
                )

    # TOB quality + edge stats (from clob_tob snapshot)
    tob_lines: List[str] = []
    if not clob_tob.empty:
        trade_keys_total = int(clob_tob["trade_key"].nunique()) if "trade_key" in clob_tob.columns else 0
        cl = clob_tob[clob_tob["outcome"].isin(["Up", "Down"])].copy()
        cl["lag_s"] = (cl["captured_at"] - cl["trade_at"]).dt.total_seconds().abs()
        best = cl.sort_values("lag_s").groupby(["trade_key", "outcome"], as_index=False).first()
        pivot = best.pivot(index="trade_key", columns="outcome")
        both = pivot.dropna(subset=[("best_bid_price", "Up"), ("best_bid_price", "Down")])
        both_count = int(len(both))

        lag_q = _quantiles(best["lag_s"].to_numpy(dtype=float), [0.5, 0.9, 0.95, 0.99])
        tob_lines.append(f"- `trade_key` total: {trade_keys_total:,}; with both outcomes: {both_count:,} ({(both_count / trade_keys_total * 100.0) if trade_keys_total else 0.0:.1f}%)")
        tob_lines.append(f"- `abs(captured_at - trade_at)` seconds: p50={lag_q[0.5]:.1f} p90={lag_q[0.9]:.1f} p95={lag_q[0.95]:.1f} p99={lag_q[0.99]:.1f}")

    edge_q = _edge_from_clob_tob(clob_tob) if not clob_tob.empty else {}
    edge_lines: List[str] = []
    for k, qs in edge_q.items():
        edge_lines.append(
            f"- `{k}`: p50={qs[0.5]:.3f} p90={qs[0.9]:.3f} p95={qs[0.95]:.3f}"
        )

    lines: List[str] = []
    if manifest:
        lines.append(f"# Snapshot report: `{manifest.get('snapshot_id', snap.name)}`")
        lines.append("")
        lines.append(f"- Created: `{manifest.get('created_at_utc')}`")
        lines.append(f"- Git SHA: `{manifest.get('git_sha')}`")
        lines.append("")
    else:
        lines.append(f"# Snapshot report: `{snap.name}`")
        lines.append("")

    lines.append("## Overview")
    lines.append(f"- Trades: `{total_trades:,}` (BUY: `{buy_trades:,}`)")
    lines.append(f"- Resolved: `{int(len(resolved)):,}`")
    lines.append(f"- Notional (sum price*size): `${notional:,.2f}`")
    if np.isfinite(pnl):
        lines.append(f"- Realized PnL (resolved, fee-excluded): `${pnl:,.2f}`")
    lines.append("")

    lines.append("## Market mix")
    for k in ["btc-15m", "eth-15m", "btc-1h", "eth-1h", "other"]:
        if k in series_counts:
            lines.append(f"- `{k}`: `{series_counts[k]:,}` trades")
    lines.append("")

    if outcome_lines:
        lines.append("## Outcome Balance (Sample)")
        lines.extend(outcome_lines)
        lines.append("")

    lines.append("## Pairing (UP vs DOWN)")
    if delays.size:
        lines.append(f"- Conditions with both outcomes: `{cond_both:,}/{cond_total:,}` ({(cond_both/cond_total*100.0) if cond_total else 0.0:.1f}%)")
        lines.append(f"- Nearest opposite-leg delay (seconds): p50={delay_q[0.5]:.0f} p90={delay_q[0.9]:.0f} p95={delay_q[0.95]:.0f}")
        lines.append(f"- % within 60s: {(delays <= 60).mean() * 100.0:.1f}%")
    if imbalance_lines:
        lines.extend(imbalance_lines)
    lines.append("")

    if sizing_lines:
        lines.append("## Sizing by time-to-end")
        lines.extend(sizing_lines)
        lines.append("")

    if timing_lines:
        lines.append("## Timing By Series")
        lines.extend(timing_lines)
        lines.append("")

    if tob_lines:
        lines.append("## CLOB TOB quality (trade-triggered)")
        lines.extend(tob_lines)
        lines.append("")

    if edge_lines:
        lines.append("## Complete-set edge (from CLOB TOB)")
        lines.extend(edge_lines)
        lines.append("")

    report = "\n".join(lines).rstrip() + "\n"
    print(report)

    if args.write_md:
        out_path = Path(args.write_md).expanduser().resolve()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(report)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
