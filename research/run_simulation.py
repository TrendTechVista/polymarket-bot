#!/usr/bin/env python3
"""Backtest and Monte Carlo simulation for target user."""

import pandas as pd
import numpy as np
from pathlib import Path
import clickhouse_connect

print("=" * 60)
print("GABAGOOL22 BACKTEST & MONTE CARLO SIMULATION")
print("=" * 60)

# Connect to ClickHouse
client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

tables = set(r[0] for r in client.query("SHOW TABLES").result_rows)
view = (
    "user_trade_enriched_v4"
    if "user_trade_enriched_v4" in tables
    else ("user_trade_enriched_v3" if "user_trade_enriched_v3" in tables else "user_trade_enriched_v2")
)
print(f"\nUsing view: {view}")

print("\n=== Loading enriched data from ClickHouse ===")
df = client.query_df("""
    SELECT 
        ts,
        market_slug as market_slug,
        side,
        price,
        size,
        coalesce(ws_mid, mid) AS mid,
        coalesce(ws_best_bid_price, best_bid_price) AS best_bid_price,
        coalesce(ws_best_ask_price, best_ask_price) AS best_ask_price,
        settle_price,
        seconds_to_end,
        outcome as outcome,
        coalesce(nullIf(ws_exec_type, 'UNKNOWN'), exec_type) AS exec_type
    FROM {view}
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
    AND settle_price IS NOT NULL
    ORDER BY ts
""".format(view=view))

print(f"Loaded {len(df)} resolved trades")

# Compute PnL under different scenarios
print("\n=== Computing PnL under different scenarios ===")

# Actual execution
df['pnl_actual'] = (df['settle_price'] - df['price']) * df['size']

# If traded at mid
df['pnl_mid'] = (df['settle_price'] - df['mid']) * df['size']

# If traded at bid (maker - best case for buyer)
df['pnl_maker'] = (df['settle_price'] - df['best_bid_price']) * df['size']

# If traded at ask (taker - worst case for buyer)
df['pnl_taker'] = (df['settle_price'] - df['best_ask_price']) * df['size']

# Filter valid rows
valid = df[(df['mid'] > 0) & (df['best_bid_price'] > 0) & (df['best_ask_price'] > 0)].copy()
print(f"Valid rows with complete TOB: {len(valid)}")

# Summary by scenario
print("\n=== PNL BY SCENARIO ===")
scenarios = {
    'Actual': 'pnl_actual',
    'At Mid': 'pnl_mid',
    'Maker (bid)': 'pnl_maker',
    'Taker (ask)': 'pnl_taker'
}

for name, col in scenarios.items():
    pnl = valid[col].dropna()
    print(f"{name:15}: Total ${pnl.sum():>10,.2f}, Avg ${pnl.mean():>8.2f}, Win {(pnl > 0).mean()*100:>5.1f}%")

# Monte Carlo simulation
print("\n=== MONTE CARLO SIMULATION (20,000 iterations) ===")

def block_bootstrap(pnl_array, iters=20000, block_len=50, seed=42):
    """Circular block bootstrap"""
    pnl = pnl_array[np.isfinite(pnl_array)]
    n = len(pnl)
    if n == 0:
        return None

    rng = np.random.default_rng(seed)
    totals = np.empty(iters)
    max_dds = np.empty(iters)

    for i in range(iters):
        idx = []
        while len(idx) < n:
            start = rng.integers(0, n)
            block = (start + np.arange(block_len)) % n
            idx.extend(block.tolist())
        idx = np.array(idx[:n])
        sample = pnl[idx]
        totals[i] = sample.sum()

        # Max drawdown
        equity = np.cumsum(sample)
        peak = np.maximum.accumulate(equity)
        dd = peak - equity
        max_dds[i] = np.max(dd)

    return {
        'total_pnl': {
            'p05': np.percentile(totals, 5),
            'p50': np.percentile(totals, 50),
            'p95': np.percentile(totals, 95),
        },
        'max_dd': {
            'p05': np.percentile(max_dds, 5),
            'p50': np.percentile(max_dds, 50),
            'p95': np.percentile(max_dds, 95),
        }
    }

for name, col in scenarios.items():
    pnl = valid[col].dropna().values
    result = block_bootstrap(pnl)
    if result:
        print(f"\n{name}:")
        print(f"  Total PnL: 5th=${result['total_pnl']['p05']:>8,.0f}, "
              f"50th=${result['total_pnl']['p50']:>8,.0f}, "
              f"95th=${result['total_pnl']['p95']:>8,.0f}")
        print(f"  Max DD:    5th=${result['max_dd']['p05']:>8,.0f}, "
              f"50th=${result['max_dd']['p50']:>8,.0f}, "
              f"95th=${result['max_dd']['p95']:>8,.0f}")

# Sharpe-like ratio
print("\n=== RISK METRICS ===")
for name, col in scenarios.items():
    pnl = valid[col].dropna()
    if len(pnl) > 1 and pnl.std() > 0:
        sharpe = (pnl.mean() / pnl.std()) * np.sqrt(252 * 24)  # Annualized hourly
        print(f"{name:15}: Sharpe-like = {sharpe:.2f}")

# By market type
print("\n=== MONTE CARLO BY MARKET TYPE ===")
def market_type(slug):
    if 'btc-updown-15m' in str(slug):
        return '15min-BTC'
    elif 'eth-updown-15m' in str(slug):
        return '15min-ETH'
    elif 'bitcoin-up-or-down' in str(slug):
        return '1hour-BTC'
    elif 'ethereum-up-or-down' in str(slug):
        return '1hour-ETH'
    return 'other'

valid['mtype'] = valid['market_slug'].apply(market_type)

for mtype in ['15min-BTC', '15min-ETH', '1hour-BTC', '1hour-ETH']:
    subset = valid[valid['mtype'] == mtype]['pnl_actual'].dropna().values
    if len(subset) > 100:
        result = block_bootstrap(subset, iters=5000)
        print(f"{mtype}: 5th=${result['total_pnl']['p05']:>6,.0f}, "
              f"50th=${result['total_pnl']['p50']:>6,.0f}, "
              f"95th=${result['total_pnl']['p95']:>6,.0f}")

print("\n" + "=" * 60)
print("SIMULATION COMPLETE")
print("=" * 60)
