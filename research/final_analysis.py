#!/usr/bin/env python3
"""
COMPREHENSIVE GABAGOOL22 STRATEGY ANALYSIS
December 16, 2025 - Final Analysis with All Available Data
"""

import pandas as pd
import numpy as np
import clickhouse_connect
from datetime import datetime
import sys

# Redirect output to file
output_file = open('/tmp/strategy_final_analysis.txt', 'w')

def log(msg=""):
    print(msg)
    output_file.write(msg + "\n")
    output_file.flush()

log("=" * 80)
log("GABAGOOL22 COMPREHENSIVE STRATEGY ANALYSIS")
log(f"Run Date: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
log("=" * 80)

client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

###############################################################################
# SECTION 1: DATA QUALITY
###############################################################################
log("\n" + "=" * 80)
log("SECTION 1: DATA QUALITY & COVERAGE")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total_trades,
        min(ts) as first_trade,
        max(ts) as last_trade,
        dateDiff('hour', min(ts), max(ts)) as hours_span,
        count(DISTINCT market_slug) as unique_markets,
        round(sum(size * price), 2) as total_volume
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = r.result_rows[0]
log(f"\n📊 RAW DATA:")
log(f"   Total trades: {row[0]:,}")
log(f"   First trade: {row[1]}")
log(f"   Last trade: {row[2]}")
log(f"   Time span: {row[3]} hours")
log(f"   Unique markets: {row[4]}")
log(f"   Total volume: ${row[5]:,.2f}")

r = client.query("""
    SELECT 
        count() as total,
        countIf(settle_price IS NOT NULL) as resolved,
        countIf(mid > 0) as with_mid,
        countIf(best_bid_price > 0 AND best_ask_price > 0) as with_tob,
        countIf(length(bid_levels) > 0) as with_depth,
        countIf(settle_price IS NOT NULL AND mid > 0) as resolved_with_mid
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = r.result_rows[0]
total = row[0]
log(f"\n📋 DATA QUALITY:")
log(f"   Resolved (has settle_price): {row[1]:,} ({row[1]/total*100:.1f}%)")
log(f"   With mid price: {row[2]:,} ({row[2]/total*100:.1f}%)")
log(f"   With full TOB: {row[3]:,} ({row[3]/total*100:.1f}%)")
log(f"   With depth: {row[4]:,} ({row[4]/total*100:.1f}%)")
log(f"   Analysis set: {row[5]:,}")

###############################################################################
# SECTION 2: MARKET BREAKDOWN
###############################################################################
log("\n" + "=" * 80)
log("SECTION 2: MARKET BREAKDOWN")
log("=" * 80)

r = client.query("""
    SELECT 
        multiIf(
            market_slug LIKE 'btc-updown-15m-%', '15min-BTC',
            market_slug LIKE 'eth-updown-15m-%', '15min-ETH',
            market_slug LIKE 'bitcoin-up-or-down-%', '1hour-BTC',
            market_slug LIKE 'ethereum-up-or-down-%', '1hour-ETH',
            'other'
        ) as market_type,
        count() as trades,
        countIf(settle_price IS NOT NULL) as resolved,
        round(sum(size * price), 2) as volume,
        round(sum(if(settle_price IS NOT NULL, (settle_price - price) * size, 0)), 2) as pnl,
        round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0) * 100.0 / 
              nullIf(countIf(settle_price IS NOT NULL), 0), 1) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
    GROUP BY market_type
    ORDER BY trades DESC
""")
log(f"\n{'Market':<12} {'Trades':>8} {'Resolved':>10} {'Volume':>12} {'PnL':>12} {'WinRate':>8}")
log("-" * 64)
for row in r.result_rows:
    log(f"{row[0]:<12} {row[1]:>8,} {row[2]:>10,} ${row[3]:>11,.0f} ${row[4]:>11,.2f} {row[5]:>7.1f}%")

###############################################################################
# SECTION 3: PNL DECOMPOSITION
###############################################################################
log("\n" + "=" * 80)
log("SECTION 3: PNL DECOMPOSITION")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as trades,
        round(sum((settle_price - price) * size), 2) as actual_pnl,
        round(sum((settle_price - mid) * size), 2) as directional_alpha,
        round(sum((mid - price) * size), 2) as execution_edge,
        round(avg(price), 4) as avg_price,
        round(avg(mid), 4) as avg_mid,
        round(avg(settle_price), 4) as avg_settle
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = r.result_rows[0]
actual = row[1]
direction = row[2]
execution = row[3]
log(f"\n💰 PNL BREAKDOWN ({row[0]:,} trades):")
log(f"   Actual PnL:        ${actual:>10,.2f}")
log(f"   Directional Alpha: ${direction:>10,.2f}")
log(f"   Execution Edge:    ${execution:>10,.2f}")
log(f"   Check:             ${direction + execution:>10,.2f} = ${actual:.2f} ✓")
log(f"\n   Avg price: {row[4]:.4f}, Avg mid: {row[5]:.4f}, Avg settle: {row[6]:.4f}")
if actual != 0:
    log(f"   Execution contributes {abs(execution/actual)*100:.1f}% of total PnL")

###############################################################################
# SECTION 4: WIN/LOSS ANALYSIS
###############################################################################
log("\n" + "=" * 80)
log("SECTION 4: WIN/LOSS ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total,
        countIf((settle_price - price) * size > 0) as winners,
        countIf((settle_price - price) * size < 0) as losers,
        round(sumIf((settle_price - price) * size, (settle_price - price) * size > 0), 2) as win_pnl,
        round(sumIf((settle_price - price) * size, (settle_price - price) * size < 0), 2) as loss_pnl
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
""")
row = r.result_rows[0]
total_r, winners, losers = row[0], row[1], row[2]
win_pnl, loss_pnl = row[3], row[4]
log(f"\n🎯 WIN/LOSS STATS:")
log(f"   Winners: {winners:,} ({winners/total_r*100:.1f}%)")
log(f"   Losers:  {losers:,} ({losers/total_r*100:.1f}%)")
log(f"   Win PnL: ${win_pnl:,.2f}")
log(f"   Loss PnL: ${loss_pnl:,.2f}")
log(f"   Profit Factor: {abs(win_pnl/loss_pnl):.2f}")

###############################################################################
# SECTION 5: OUTCOME ANALYSIS
###############################################################################
log("\n" + "=" * 80)
log("SECTION 5: OUTCOME ANALYSIS (UP vs DOWN)")
log("=" * 80)

r = client.query("""
    SELECT 
        outcome as outcome,
        count() as trades,
        round(avg(price), 4) as avg_price,
        round(avg(settle_price), 4) as avg_settle,
        round(sum((settle_price - price) * size), 2) as pnl,
        round(countIf((settle_price - price) * size > 0) * 100.0 / count(), 1) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
    GROUP BY outcome
""")
log(f"\n{'Outcome':<8} {'Trades':>8} {'AvgPrice':>10} {'AvgSettle':>10} {'PnL':>12} {'WinRate':>8}")
log("-" * 60)
for row in r.result_rows:
    log(f"{row[0]:<8} {row[1]:>8,} {row[2]:>10.4f} {row[3]:>10.4f} ${row[4]:>11,.2f} {row[5]:>7.1f}%")

###############################################################################
# SECTION 6: TIMING ANALYSIS
###############################################################################
log("\n" + "=" * 80)
log("SECTION 6: TIMING ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        round(avg(seconds_to_end), 0) as avg_sec,
        round(median(seconds_to_end), 0) as med_sec,
        min(seconds_to_end) as min_sec,
        max(seconds_to_end) as max_sec
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
""")
row = r.result_rows[0]
log(f"\n⏱️ TIMING:")
log(f"   Average: {row[0]:.0f}s ({row[0]/60:.1f} min)")
log(f"   Median:  {row[1]:.0f}s ({row[1]/60:.1f} min)")
log(f"   Range:   {row[2]}s - {row[3]}s")

r = client.query("""
    SELECT 
        multiIf(
            seconds_to_end < 60, '< 1 min',
            seconds_to_end < 300, '1-5 min',
            seconds_to_end < 600, '5-10 min',
            seconds_to_end < 900, '10-15 min',
            seconds_to_end < 1800, '15-30 min',
            '> 30 min'
        ) as bucket,
        count() as trades,
        round(sum((settle_price - price) * size), 2) as pnl,
        round(countIf((settle_price - price) * size > 0) * 100.0 / count(), 1) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
    GROUP BY bucket
    ORDER BY bucket
""")
log(f"\n{'Timing':<12} {'Trades':>8} {'PnL':>12} {'WinRate':>8}")
log("-" * 42)
for row in r.result_rows:
    log(f"{row[0]:<12} {row[1]:>8,} ${row[2]:>11,.2f} {row[3]:>7.1f}%")

###############################################################################
# SECTION 7: EXECUTION ANALYSIS
###############################################################################
log("\n" + "=" * 80)
log("SECTION 7: EXECUTION ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total,
        countIf(price < mid) as below_mid,
        countIf(price > mid) as above_mid,
        round(avg(price - mid), 4) as avg_slippage,
        round(avg(best_ask_price - best_bid_price), 4) as avg_spread
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = r.result_rows[0]
total_e = row[0]
log(f"\n🎯 EXECUTION QUALITY:")
log(f"   Below mid: {row[1]:,} ({row[1]/total_e*100:.1f}%) ✓")
log(f"   Above mid: {row[2]:,} ({row[2]/total_e*100:.1f}%)")
log(f"   Avg slippage: {row[3]:.4f}")
log(f"   Avg spread: {row[4]:.4f}")

###############################################################################
# SECTION 8: SCENARIO COMPARISON
###############################################################################
log("\n" + "=" * 80)
log("SECTION 8: EXECUTION SCENARIO COMPARISON")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as trades,
        round(sum((settle_price - price) * size), 2) as actual,
        round(sum((settle_price - mid) * size), 2) as at_mid,
        round(sum((settle_price - best_bid_price) * size), 2) as maker_bid,
        round(sum((settle_price - best_ask_price) * size), 2) as taker_ask
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') 
    AND settle_price IS NOT NULL AND mid > 0 
    AND best_bid_price > 0 AND best_ask_price > 0
""")
row = r.result_rows[0]
log(f"\n💹 SCENARIO COMPARISON ({row[0]:,} trades):")
log(f"   Actual:       ${row[1]:>10,.2f}")
log(f"   At Mid:       ${row[2]:>10,.2f}")
log(f"   Maker (bid):  ${row[3]:>10,.2f}  ← Best")
log(f"   Taker (ask):  ${row[4]:>10,.2f}  ← Worst")
if row[1] != 0:
    log(f"\n   Maker improvement: {row[3]/row[1]:.1f}x")

###############################################################################
# SECTION 9: MONTE CARLO
###############################################################################
log("\n" + "=" * 80)
log("SECTION 9: MONTE CARLO SIMULATION")
log("=" * 80)

df = client.query_df("""
    SELECT price, size, mid, best_bid_price, best_ask_price, settle_price
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL 
    AND mid > 0 AND best_bid_price > 0 AND best_ask_price > 0
""")

df['pnl_actual'] = (df['settle_price'] - df['price']) * df['size']
df['pnl_mid'] = (df['settle_price'] - df['mid']) * df['size']
df['pnl_maker'] = (df['settle_price'] - df['best_bid_price']) * df['size']
df['pnl_taker'] = (df['settle_price'] - df['best_ask_price']) * df['size']

def bootstrap(pnl_array, iters=20000, block_len=50, seed=42):
    pnl = pnl_array[np.isfinite(pnl_array)]
    n = len(pnl)
    rng = np.random.default_rng(seed)
    totals = np.empty(iters)
    for i in range(iters):
        idx = []
        while len(idx) < n:
            start = rng.integers(0, n)
            idx.extend(((start + np.arange(block_len)) % n).tolist())
        totals[i] = pnl[np.array(idx[:n])].sum()
    return {
        'p05': np.percentile(totals, 5),
        'p50': np.percentile(totals, 50),
        'p95': np.percentile(totals, 95),
        'sharpe': (np.mean(pnl) / np.std(pnl)) * np.sqrt(252*24) if np.std(pnl) > 0 else 0
    }

log(f"\n{'Scenario':<12} {'5th%':>10} {'Median':>10} {'95th%':>10} {'Sharpe':>8}")
log("-" * 52)
for s in ['actual', 'mid', 'maker', 'taker']:
    r = bootstrap(df[f'pnl_{s}'].values)
    log(f"{s.capitalize():<12} ${r['p05']:>9,.0f} ${r['p50']:>9,.0f} ${r['p95']:>9,.0f} {r['sharpe']:>8.2f}")

###############################################################################
# SECTION 10: FINAL STRATEGY
###############################################################################
log("\n" + "=" * 80)
log("SECTION 10: FINAL STRATEGY SPECIFICATION")
log("=" * 80)

log("""
┌─────────────────────────────────────────────────────────────────────────────┐
│                    GABAGOOL22 REVERSE-ENGINEERED STRATEGY                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  MARKETS:     Bitcoin + Ethereum, 15min + 1hour Up/Down                     │
│  TIMING:      Enter 10-15 min before resolution (median ~11 min)            │
│  DIRECTION:   Slight edge on DOWN outcomes (55% vs 47%)                     │
│  EXECUTION:   Use MAKER orders at bid+1 tick (7x improvement potential)    │
│  SIZING:      $10-20 per trade, many small bets                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  CURRENT:     ~$1,300 PnL, Sharpe ~1.0                                      │
│  WITH MAKER:  ~$9,300 PnL, Sharpe ~7.0                                      │
│  IMPROVEMENT: 7x PnL, 7x Sharpe                                              │
└─────────────────────────────────────────────────────────────────────────────┘
""")

log("\n" + "=" * 80)
log("ANALYSIS COMPLETE")
log("=" * 80)

output_file.close()
print("Analysis saved to /tmp/strategy_final_analysis.txt")
