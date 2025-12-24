#!/usr/bin/env python3
"""Deep dive into target user execution quality."""

import pandas as pd
from pathlib import Path
import sys

output_lines = []

def log(msg):
    output_lines.append(msg)
    print(msg, flush=True)

snapshot = Path('/Users/antoniostano/programming/polybot/research/data/snapshots/target user-20251216T113830+0000')
df = pd.read_parquet(snapshot / 'features.parquet')

df['settle_price'] = pd.to_numeric(df.get('settle_price'), errors='coerce')
df['mid'] = pd.to_numeric(df.get('mid'), errors='coerce')
resolved = df[df['settle_price'].notna()].copy()

# Compare actual price vs mid
resolved['has_mid'] = resolved['mid'].notna()
log(f'Trades with mid price: {resolved["has_mid"].sum()}')
log(f'Trades without mid: {(~resolved["has_mid"]).sum()}')

with_mid = resolved[resolved['has_mid']].copy()
log(f'\nAvg price: {with_mid["price"].mean():.4f}')
log(f'Avg mid: {with_mid["mid"].mean():.4f}')
log(f'Avg (price - mid): {(with_mid["price"] - with_mid["mid"]).mean():.4f}')

# For BUY orders, price > mid means he paid MORE than mid
# This would mean worse execution
log(f'\nPaid MORE than mid: {(with_mid["price"] > with_mid["mid"]).sum()}')
log(f'Paid LESS than mid: {(with_mid["price"] < with_mid["mid"]).sum()}')
log(f'Paid AT mid: {(with_mid["price"] == with_mid["mid"]).sum()}')

# Calculate execution PnL impact
with_mid['exec_impact'] = (with_mid['mid'] - with_mid['price']) * with_mid['size']
log(f'\nExecution impact (mid - actual price): ${with_mid["exec_impact"].sum():.2f}')
log('Positive = got better than mid, Negative = paid more than mid')

# What about if he had traded at mid?
def calc_pnl_at_mid(row):
    settle = row['settle_price']
    mid = row['mid']
    size = row['size']
    return (settle - mid) * size  # BUY: (settle - entry) * size

with_mid['pnl_at_mid'] = with_mid.apply(calc_pnl_at_mid, axis=1)
with_mid['pnl_actual'] = (with_mid['settle_price'] - with_mid['price']) * with_mid['size']

log(f'\nActual PnL (with mid subset): ${with_mid["pnl_actual"].sum():.2f}')
log(f'Mid PnL (if traded at mid): ${with_mid["pnl_at_mid"].sum():.2f}')
log(f'Execution edge: ${with_mid["pnl_actual"].sum() - with_mid["pnl_at_mid"].sum():.2f}')

# Write to file
with open('/Users/antoniostano/programming/polybot/research/exec_output.txt', 'w') as f:
    f.write('\n'.join(output_lines))

