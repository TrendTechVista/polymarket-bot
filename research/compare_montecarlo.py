#!/usr/bin/env python3
"""
Compare Monte Carlo implementations:
1. Terminal script (deep_analysis.py) - uses ClickHouse directly
2. Notebook style (backtest.py) - uses snapshot parquet files
"""

import pandas as pd
import numpy as np
import clickhouse_connect
import sys
from pathlib import Path

print("=" * 80)
print("MONTE CARLO COMPARISON: Terminal vs Notebook")
print("=" * 80)

# =============================================================================
# METHOD 1: TERMINAL STYLE (Direct from ClickHouse)
# =============================================================================
print("\n" + "=" * 80)
print("METHOD 1: TERMINAL (ClickHouse Direct)")
print("=" * 80)

client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

df_ch = client.query_df("""
    SELECT price, size, mid, best_bid_price, best_ask_price, settle_price
    FROM user_trade_enriched_v2
    WHERE username = 'gabagool22' AND settle_price IS NOT NULL 
    AND mid > 0 AND best_bid_price > 0 AND best_ask_price > 0
""")

print(f"Loaded {len(df_ch)} trades from ClickHouse")

df_ch['pnl_actual'] = (df_ch['settle_price'] - df_ch['price']) * df_ch['size']
df_ch['pnl_mid'] = (df_ch['settle_price'] - df_ch['mid']) * df_ch['size']
df_ch['pnl_maker'] = (df_ch['settle_price'] - df_ch['best_bid_price']) * df_ch['size']
df_ch['pnl_taker'] = (df_ch['settle_price'] - df_ch['best_ask_price']) * df_ch['size']

def bootstrap_terminal(pnl_array, iters=20000, block_len=50, seed=42):
    """Terminal-style bootstrap (per-trade level)"""
    pnl = pnl_array[np.isfinite(pnl_array)]
    n = len(pnl)
    rng = np.random.default_rng(seed)
    totals = np.empty(iters)
    max_dds = np.empty(iters)
    for i in range(iters):
        idx = []
        while len(idx) < n:
            start = rng.integers(0, n)
            idx.extend(((start + np.arange(block_len)) % n).tolist())
        sample = pnl[np.array(idx[:n])]
        totals[i] = sample.sum()
        equity = np.cumsum(sample)
        peak = np.maximum.accumulate(equity)
        max_dds[i] = np.max(peak - equity)
    return {
        'p05': np.percentile(totals, 5),
        'p50': np.percentile(totals, 50),
        'p95': np.percentile(totals, 95),
        'dd_p50': np.percentile(max_dds, 50),
        'dd_p95': np.percentile(max_dds, 95),
    }

print(f"\n{'Scenario':<12} {'Total':>12} {'Boot p05':>12} {'Boot p50':>12} {'Boot p95':>12}")
print("-" * 60)
terminal_results = {}
for s in ['actual', 'mid', 'maker', 'taker']:
    pnl = df_ch[f'pnl_{s}'].values
    r = bootstrap_terminal(pnl)
    terminal_results[s] = r
    print(f"{s:<12} ${pnl.sum():>11,.0f} ${r['p05']:>11,.0f} ${r['p50']:>11,.0f} ${r['p95']:>11,.0f}")

# =============================================================================
# METHOD 2: NOTEBOOK STYLE (From snapshot, with units)
# =============================================================================
print("\n" + "=" * 80)
print("METHOD 2: NOTEBOOK (Snapshot with Units)")
print("=" * 80)

# Load from snapshot
snapshot_path = Path("/Users/antoniostano/programming/polybot/research/data/snapshots/gabagool22-20251216T170445+0000")
if not (snapshot_path / "features.parquet").exists():
    print(f"No features.parquet in {snapshot_path}, using trades.parquet")
    df_snap = pd.read_parquet(snapshot_path / "trades.parquet")
else:
    df_snap = pd.read_parquet(snapshot_path / "features.parquet")

print(f"Loaded {len(df_snap)} trades from snapshot")

# Filter to resolved with TOB
df_snap = df_snap[
    (df_snap['settle_price'].notna()) &
    (df_snap['mid'] > 0) &
    (df_snap['best_bid_price'] > 0) &
    (df_snap['best_ask_price'] > 0)
].copy()
print(f"After filtering: {len(df_snap)} trades")

# Compute PnL
df_snap['pnl_actual'] = (df_snap['settle_price'] - df_snap['price']) * df_snap['size']
df_snap['pnl_mid'] = (df_snap['settle_price'] - df_snap['mid']) * df_snap['size']
df_snap['pnl_maker'] = (df_snap['settle_price'] - df_snap['best_bid_price']) * df_snap['size']
df_snap['pnl_taker'] = (df_snap['settle_price'] - df_snap['best_ask_price']) * df_snap['size']

# Build units (aggregate by market + bucket)
def build_units_simple(df, pnl_col):
    """Aggregate trades into decision units"""
    if 'bucket' not in df.columns:
        df = df.copy()
        df['bucket'] = (df['ts'].astype('int64') // 10**9 // 10) * 10

    group_cols = ['market_slug', 'bucket'] if 'market_slug' in df.columns else ['bucket']
    units = df.groupby(group_cols, as_index=False).agg(
        pnl=(pnl_col, 'sum'),
        trades=(pnl_col, 'size')
    )
    return units

def bootstrap_notebook(pnl_array, iters=20000, block_len=50, seed=7):
    """Notebook-style bootstrap (uses seed=7 like backtest.py)"""
    pnl = pnl_array[np.isfinite(pnl_array)]
    n = len(pnl)
    rng = np.random.default_rng(seed)
    totals = np.empty(iters)
    max_dds = np.empty(iters)
    for i in range(iters):
        idx = []
        while len(idx) < n:
            start = rng.integers(0, n)
            idx.extend(((start + np.arange(block_len)) % n).tolist())
        sample = pnl[np.array(idx[:n])]
        totals[i] = sample.sum()
        equity = np.cumsum(sample)
        peak = np.maximum.accumulate(equity)
        max_dds[i] = np.max(peak - equity)
    return {
        'p05': np.percentile(totals, 5),
        'p50': np.percentile(totals, 50),
        'p95': np.percentile(totals, 95),
        'dd_p50': np.percentile(max_dds, 50),
        'dd_p95': np.percentile(max_dds, 95),
    }

print(f"\n{'Scenario':<12} {'Total':>12} {'Units':>8} {'Boot p05':>12} {'Boot p50':>12} {'Boot p95':>12}")
print("-" * 68)
notebook_results = {}
for s in ['actual', 'mid', 'maker', 'taker']:
    pnl_col = f'pnl_{s}'
    units = build_units_simple(df_snap, pnl_col)
    r = bootstrap_notebook(units['pnl'].values)
    notebook_results[s] = r
    notebook_results[s]['n_units'] = len(units)
    print(f"{s:<12} ${df_snap[pnl_col].sum():>11,.0f} {len(units):>8,} ${r['p05']:>11,.0f} ${r['p50']:>11,.0f} ${r['p95']:>11,.0f}")

# =============================================================================
# COMPARISON
# =============================================================================
print("\n" + "=" * 80)
print("COMPARISON: KEY DIFFERENCES")
print("=" * 80)

print("""
┌─────────────────────────────────────────────────────────────────────────────┐
│ DIFFERENCE              │ TERMINAL             │ NOTEBOOK                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ Data Source             │ ClickHouse (live)    │ Snapshot (parquet)         │
│ Bootstrap Level         │ Per-trade            │ Per-unit (market+bucket)   │
│ Seed                    │ 42                   │ 7                          │
│ Aggregation             │ None                 │ Groups by market+10s bucket│
│ Effect                  │ Higher variance      │ Lower variance (smoother)  │
└─────────────────────────────────────────────────────────────────────────────┘
""")

print("\nMedian PnL Comparison:")
print(f"{'Scenario':<12} {'Terminal':>12} {'Notebook':>12} {'Diff':>12}")
print("-" * 50)
for s in ['actual', 'mid', 'maker', 'taker']:
    t = terminal_results[s]['p50']
    n = notebook_results[s]['p50']
    diff = t - n
    print(f"{s:<12} ${t:>11,.0f} ${n:>11,.0f} ${diff:>11,.0f}")

print("\n✅ COMPARISON COMPLETE")
print("""
KEY INSIGHT: 
- Terminal bootstrap operates on individual trades (more granular)
- Notebook bootstrap aggregates into "units" first (reduces autocorrelation)
- Both are valid approaches, notebook is more conservative
""")

