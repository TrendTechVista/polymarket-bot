#!/usr/bin/env python3
"""Comprehensive target user analysis with latest data."""

import clickhouse_connect
import pandas as pd
import numpy as np
from pathlib import Path

# Connect to ClickHouse
client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

output = []

def log(msg):
    print(msg)
    output.append(msg)

log("=" * 60)
log("COMPREHENSIVE GABAGOOL22 ANALYSIS")
log("=" * 60)

# 1. Data Summary
log("\n=== DATA SUMMARY ===")
result = client.query("""
    SELECT 
        count() as total_trades,
        countIf(settle_price IS NOT NULL) as resolved_trades,
        countIf(mid > 0) as with_tob,
        countIf(length(bid_levels) > 0) as with_depth,
        round(sum(size * price), 2) as total_volume,
        min(ts) as first_trade,
        max(ts) as last_trade
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = result.result_rows[0]
log(f"Total trades: {row[0]:,}")
log(f"Resolved trades: {row[1]:,}")
log(f"With TOB data: {row[2]:,}")
log(f"With order book depth: {row[3]:,}")
log(f"Total volume: ${row[4]:,.2f}")
log(f"First trade: {row[5]}")
log(f"Last trade: {row[6]}")

# 2. PnL Summary
log("\n=== PNL SUMMARY ===")
result = client.query("""
    SELECT 
        count() as trades,
        round(sum(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size)), 2) as actual_pnl,
        round(sum(if(side = 'BUY', (settle_price - mid) * size, (mid - settle_price) * size)), 2) as mid_pnl,
        round(avg(price), 4) as avg_price,
        round(avg(mid), 4) as avg_mid
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = result.result_rows[0]
actual_pnl = row[1]
mid_pnl = row[2]
exec_cost = actual_pnl - mid_pnl
log(f"Trades with TOB: {row[0]:,}")
log(f"Actual PnL: ${actual_pnl:,.2f}")
log(f"If traded at mid: ${mid_pnl:,.2f}")
log(f"Execution impact: ${exec_cost:,.2f}")
log(f"Avg execution price: {row[3]:.4f}")
log(f"Avg mid price: {row[4]:.4f}")

# 3. Win/Loss Stats
log("\n=== WIN/LOSS STATS ===")
result = client.query("""
    SELECT 
        count() as total,
        countIf(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size) > 0) as winners,
        countIf(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size) < 0) as losers,
        countIf(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size) = 0) as breakeven
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
""")
row = result.result_rows[0]
total = row[0]
winners = row[1]
losers = row[2]
log(f"Total resolved: {total:,}")
log(f"Winners: {winners:,} ({winners/total*100:.1f}%)")
log(f"Losers: {losers:,} ({losers/total*100:.1f}%)")
log(f"Win rate: {winners/total*100:.2f}%")

# 4. Execution Quality
log("\n=== EXECUTION QUALITY ===")
result = client.query("""
    SELECT 
        count() as trades,
        countIf(price > mid) as paid_more_than_mid,
        countIf(price < mid) as paid_less_than_mid,
        round(avg(price - mid), 4) as avg_slippage,
        round(sum((mid - price) * size), 2) as total_exec_impact
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = result.result_rows[0]
log(f"Paid MORE than mid: {row[1]:,} ({row[1]/row[0]*100:.1f}%)")
log(f"Paid LESS than mid: {row[2]:,} ({row[2]/row[0]*100:.1f}%)")
log(f"Avg slippage: {row[3]:.4f}")
log(f"Total execution impact: ${row[4]:,.2f}")

# 5. By Market Type
log("\n=== PNL BY MARKET TYPE ===")
result = client.query("""
    SELECT 
        CASE 
            WHEN market_slug LIKE 'btc-updown-15m-%' THEN '15min-BTC'
            WHEN market_slug LIKE 'eth-updown-15m-%' THEN '15min-ETH'
            WHEN market_slug LIKE 'bitcoin-up-or-down-%' THEN '1hour-BTC'
            WHEN market_slug LIKE 'ethereum-up-or-down-%' THEN '1hour-ETH'
            ELSE 'other'
        END as market_type,
        count() as trades,
        round(sum(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size)), 2) as actual_pnl,
        round(countIf(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size) > 0) * 100.0 / count(), 1) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
    GROUP BY market_type
    ORDER BY trades DESC
""")
for row in result.result_rows:
    log(f"  {row[0]}: {row[1]:,} trades, PnL ${row[2]:,.2f}, Win {row[3]:.1f}%")

# 6. Timing Analysis
log("\n=== TIMING ANALYSIS ===")
result = client.query("""
    SELECT 
        round(avg(seconds_to_end), 0) as avg_seconds,
        round(median(seconds_to_end), 0) as median_seconds,
        min(seconds_to_end) as min_seconds,
        max(seconds_to_end) as max_seconds
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
""")
row = result.result_rows[0]
log(f"Avg seconds to end: {row[0]:.0f} ({row[0]/60:.1f} min)")
log(f"Median seconds to end: {row[1]:.0f} ({row[1]/60:.1f} min)")
log(f"Min: {row[2]}s, Max: {row[3]}s")

# 7. Timing buckets
result = client.query("""
    SELECT 
        multiIf(
            seconds_to_end < 60, '<1min',
            seconds_to_end < 180, '1-3min',
            seconds_to_end < 300, '3-5min',
            seconds_to_end < 600, '5-10min',
            seconds_to_end < 900, '10-15min',
            '>15min'
        ) as timing,
        count() as trades,
        round(sum(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size)), 2) as pnl,
        round(countIf(if(side = 'BUY', (settle_price - price) * size, (price - settle_price) * size) > 0) * 100.0 / count(), 1) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
    GROUP BY timing
    ORDER BY timing
""")
log("\nBy timing bucket:")
for row in result.result_rows:
    log(f"  {row[0]:>8}: {row[1]:>5} trades, PnL ${row[2]:>8.2f}, Win {row[3]:>5.1f}%")

# 8. Order book depth data quality
log("\n=== ORDER BOOK DEPTH ===")
result = client.query("""
    SELECT 
        count() as total,
        countIf(length(bid_levels) > 0) as with_depth,
        round(avg(if(length(bid_levels) > 0, total_bid_volume, NULL)), 2) as avg_bid_vol,
        round(avg(if(length(bid_levels) > 0, book_imbalance, NULL)), 4) as avg_imbalance
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = result.result_rows[0]
log(f"Trades with depth: {row[1]:,} / {row[0]:,} ({row[1]/row[0]*100:.1f}%)")
if row[2]:
    log(f"Avg bid volume: ${row[2]:,.2f}")
if row[3]:
    log(f"Avg book imbalance: {row[3]:.4f}")

# Summary
log("\n" + "=" * 60)
log("SUMMARY")
log("=" * 60)
log(f"Total trades: {total:,}")
log(f"Win rate: {winners/total*100:.2f}%")
log(f"Actual PnL: ${actual_pnl:,.2f}")
log(f"Execution impact: ${exec_cost:,.2f}")
if mid_pnl != 0:
    log(f"% of alpha lost to execution: {abs(exec_cost)/abs(mid_pnl)*100:.1f}%" if mid_pnl > 0 else "N/A")

# Save output
with open('/tmp/strategy_analysis.txt', 'w') as f:
    f.write('\n'.join(output))

print("\n\nAnalysis saved to /tmp/strategy_analysis.txt")
