#!/usr/bin/env python3
"""Run full analysis on latest target user snapshot."""

import pandas as pd
import numpy as np
from pathlib import Path
import os

# Use absolute path - resolve from script location
script_dir = Path(os.path.dirname(os.path.abspath(__file__)))
snapshots_dir = script_dir / 'data' / 'snapshots'

print(f'Script dir: {script_dir}')
print(f'Snapshots dir: {snapshots_dir}')

# Find latest snapshot with features.parquet
snapshots = sorted([d for d in snapshots_dir.iterdir()
                   if d.is_dir() and os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') in d.name and (d / 'features.parquet').exists()])
if not snapshots:
    raise FileNotFoundError("No snapshots with features.parquet found")
snapshot_path = snapshots[-1]
print(f'Using snapshot: {snapshot_path}')

# Load features
features_path = snapshot_path / 'features.parquet'
print(f'Features path: {features_path}')
df = pd.read_parquet(features_path)
df['ts'] = pd.to_datetime(df['ts'], errors='coerce', utc=True)
df['settle_price'] = pd.to_numeric(df.get('settle_price'), errors='coerce')

resolved = df[df['settle_price'].notna()].copy()

print(f'\n=== Data Summary ===')
print(f'Total trades: {len(df):,}')
print(f'Resolved trades: {len(resolved):,}')
print(f'Unique markets: {df["market_slug"].nunique():,}')

# Calculate PnL
def calc_pnl(row, use_price='price'):
    price = row[use_price] if use_price in row and pd.notna(row[use_price]) else row['price']
    settle = row['settle_price']
    size = row['size']
    side = row['side']
    if side == 'BUY':
        return (settle - price) * size
    else:
        return (price - settle) * size

resolved['pnl_actual'] = resolved.apply(lambda r: calc_pnl(r, 'price'), axis=1)
resolved['pnl_mid'] = resolved.apply(lambda r: calc_pnl(r, 'mid'), axis=1)

print(f'\n=== PnL Summary ===')
print(f'Actual PnL: ${resolved["pnl_actual"].sum():,.2f}')
print(f'Mid PnL: ${resolved["pnl_mid"].sum():,.2f}')
print(f'Gap (actual - mid): ${resolved["pnl_actual"].sum() - resolved["pnl_mid"].sum():,.2f}')

# Complete-set detection
def detect_complete_sets(df, time_window_sec=60):
    pairs = []
    seen = set()

    buys = df[(df['side'] == 'BUY') & (df['outcome'].isin(['Up', 'Down', 'Yes', 'No']))].copy()

    for market, grp in buys.groupby('market_slug'):
        grp = grp.sort_values('ts').reset_index(drop=True)
        outcomes = grp['outcome'].unique()

        if len(outcomes) < 2:
            continue

        for i, row in grp.iterrows():
            if (market, i) in seen:
                continue

            opposite = grp[
                (grp['outcome'] != row['outcome']) &
                (abs((grp['ts'] - row['ts']).dt.total_seconds()) <= time_window_sec) &
                (~grp.index.isin([idx for m, idx in seen if m == market]))
            ]

            if opposite.empty:
                continue

            opposite = opposite.copy()
            opposite['time_gap'] = abs((opposite['ts'] - row['ts']).dt.total_seconds())
            match = opposite.loc[opposite['time_gap'].idxmin()]
            match_idx = opposite['time_gap'].idxmin()

            seen.add((market, i))
            seen.add((market, match_idx))

            min_size = min(row['size'], match['size'])
            combined_cost = row['price'] + match['price']
            edge = 1 - combined_cost

            pairs.append({
                'market_slug': market,
                'outcome_1': row['outcome'],
                'price_1': row['price'],
                'size_1': row['size'],
                'outcome_2': match['outcome'],
                'price_2': match['price'],
                'size_2': match['size'],
                'matched_size': min_size,
                'combined_cost': combined_cost,
                'edge': edge,
                'edge_pnl': edge * min_size,
                'time_gap_sec': match['time_gap'],
            })

    return pd.DataFrame(pairs)

print(f'\n=== Complete-Set Detection (60s window) ===')
complete_sets = detect_complete_sets(resolved, time_window_sec=60)

if len(complete_sets) > 0:
    total_edge_pnl = complete_sets['edge_pnl'].sum()
    positive_edge = (complete_sets['edge'] > 0).sum()

    print(f'Found {len(complete_sets):,} complete-set pairs')
    print(f'Positive edge pairs: {positive_edge:,} / {len(complete_sets):,} ({positive_edge/len(complete_sets)*100:.1f}%)')
    print(f'Total edge PnL: ${total_edge_pnl:,.2f}')
    print(f'Avg edge per set: {complete_sets["edge"].mean()*100:.2f}%')
    print(f'Avg matched size: {complete_sets["matched_size"].mean():.1f} shares')

    # Save complete sets
    complete_sets.to_csv(snapshot_path / 'complete_sets.csv', index=False)
    print(f'\nSaved to: {snapshot_path / "complete_sets.csv"}')
else:
    print('No complete-set pairs found.')
    total_edge_pnl = 0

# PnL gap analysis
actual_pnl = resolved['pnl_actual'].sum()
mid_pnl = resolved['pnl_mid'].sum()
pnl_gap = actual_pnl - mid_pnl

print(f'\n=== PnL Gap Analysis ===')
print(f'Actual PnL: ${actual_pnl:,.2f}')
print(f'Mid PnL: ${mid_pnl:,.2f}')
print(f'Gap: ${pnl_gap:,.2f}')
print(f'Complete-set edge: ${total_edge_pnl:,.2f}')
if pnl_gap > 0:
    print(f'Complete-sets explain: {total_edge_pnl/pnl_gap*100:.1f}% of gap')
print(f'Remaining (directional alpha): ${pnl_gap - total_edge_pnl:,.2f}')

# Execution quality analysis
print(f'\n=== Execution Quality ===')
if 'exec_type' in resolved.columns:
    exec_dist = resolved['exec_type'].value_counts()
    print(exec_dist)
else:
    # Calculate from mid
    resolved['exec_quality'] = np.where(
        resolved['side'] == 'BUY',
        np.where(resolved['price'] < resolved['mid'], 'BETTER',
                 np.where(resolved['price'] > resolved['mid'], 'WORSE', 'AT_MID')),
        np.where(resolved['price'] > resolved['mid'], 'BETTER',
                 np.where(resolved['price'] < resolved['mid'], 'WORSE', 'AT_MID'))
    )
    print(resolved['exec_quality'].value_counts())

# Timing analysis
print(f'\n=== Timing Analysis ===')
if 'seconds_to_end' in resolved.columns:
    resolved['seconds_to_end'] = pd.to_numeric(resolved['seconds_to_end'], errors='coerce')
    valid_timing = resolved[resolved['seconds_to_end'].notna() & (resolved['seconds_to_end'] > 0)]
    if len(valid_timing) > 0:
        print(f'Trades with timing: {len(valid_timing):,}')
        print(f'Median seconds to end: {valid_timing["seconds_to_end"].median():.0f}')
        print(f'Mean seconds to end: {valid_timing["seconds_to_end"].mean():.0f}')

        # Timing buckets
        buckets = pd.cut(valid_timing['seconds_to_end'],
                        bins=[0, 60, 180, 300, 600, 900, float('inf')],
                        labels=['<1m', '1-3m', '3-5m', '5-10m', '10-15m', '>15m'])
        print(f'\nTiming distribution:')
        print(buckets.value_counts().sort_index())
else:
    print('No timing data available')

print(f'\n=== Analysis Complete ===')

