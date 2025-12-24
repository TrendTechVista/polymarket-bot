#!/usr/bin/env python3
"""
GABAGOOL22 DEEP ANALYSIS - December 16, 2025
Comprehensive analysis with all latest data
"""

import pandas as pd
import numpy as np
import clickhouse_connect
from datetime import datetime

# Output file
out = open('/tmp/strategy_deep_analysis.txt', 'w')

def log(msg=""):
    print(msg)
    out.write(msg + "\n")
    out.flush()

log("=" * 80)
log("GABAGOOL22 DEEP ANALYSIS")
log(f"Timestamp: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
log("=" * 80)

client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

# =============================================================================
# SECTION 1: DATA OVERVIEW
# =============================================================================
log("\n" + "=" * 80)
log("1. DATA OVERVIEW")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total,
        countIf(settle_price IS NOT NULL) as resolved,
        countIf(mid > 0) as with_mid,
        countIf(best_bid_price > 0 AND best_ask_price > 0) as with_tob,
        countIf(length(bid_levels) > 0) as with_depth,
        countIf(seconds_to_end IS NOT NULL) as with_timing,
        round(sum(size * price), 2) as volume,
        min(ts) as first_ts,
        max(ts) as last_ts,
        dateDiff('hour', min(ts), max(ts)) as hours
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = r.result_rows[0]
total = row[0]
log(f"\n📊 DATASET SIZE:")
log(f"   Total trades:      {row[0]:,}")
log(f"   Resolved:          {row[1]:,} ({row[1]/total*100:.1f}%)")
log(f"   With mid:          {row[2]:,} ({row[2]/total*100:.1f}%)")
log(f"   With TOB:          {row[3]:,} ({row[3]/total*100:.1f}%)")
log(f"   With depth:        {row[4]:,} ({row[4]/total*100:.1f}%)")
log(f"   With timing:       {row[5]:,} ({row[5]/total*100:.1f}%)")
log(f"   Total volume:      ${row[6]:,.2f}")
log(f"   Time span:         {row[9]} hours ({row[7]} to {row[8]})")

# =============================================================================
# SECTION 2: MARKET BREAKDOWN
# =============================================================================
log("\n" + "=" * 80)
log("2. MARKET BREAKDOWN")
log("=" * 80)

r = client.query("""
    SELECT 
        multiIf(
            market_slug LIKE 'btc-updown-15m-%', '15min-BTC',
            market_slug LIKE 'eth-updown-15m-%', '15min-ETH',
            market_slug LIKE 'bitcoin-up-or-down-%', '1hour-BTC',
            market_slug LIKE 'ethereum-up-or-down-%', '1hour-ETH',
            'other'
        ) as mtype,
        count() as trades,
        countIf(settle_price IS NOT NULL) as resolved,
        round(sum(size * price), 2) as volume,
        round(sumIf((settle_price - price) * size, settle_price IS NOT NULL), 2) as pnl,
        round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0) * 100.0 / 
              nullIf(countIf(settle_price IS NOT NULL), 0), 2) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
    GROUP BY mtype
    ORDER BY pnl DESC
""")
log(f"\n{'Market':<12} {'Trades':>8} {'Resolved':>10} {'Volume':>12} {'PnL':>12} {'WinRate':>8}")
log("-" * 66)
total_pnl = 0
for row in r.result_rows:
    log(f"{row[0]:<12} {row[1]:>8,} {row[2]:>10,} ${row[3]:>11,.0f} ${row[4]:>11,.2f} {row[5]:>7.2f}%")
    total_pnl += row[4]
log("-" * 66)
log(f"{'TOTAL':<12} {'':<8} {'':<10} {'':<12} ${total_pnl:>11,.2f}")

# =============================================================================
# SECTION 3: PNL DECOMPOSITION
# =============================================================================
log("\n" + "=" * 80)
log("3. PNL DECOMPOSITION")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as n,
        round(sum((settle_price - price) * size), 2) as actual,
        round(sum((settle_price - mid) * size), 2) as direction,
        round(sum((mid - price) * size), 2) as execution,
        round(avg(price), 4) as avg_price,
        round(avg(mid), 4) as avg_mid,
        round(avg(settle_price), 4) as avg_settle
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = r.result_rows[0]
actual, direction, execution = row[1], row[2], row[3]
log(f"\n💰 PNL BREAKDOWN ({row[0]:,} trades with TOB):")
log(f"   Actual PnL:        ${actual:>10,.2f}")
log(f"   Directional Alpha: ${direction:>10,.2f} ({abs(direction/actual)*100:.1f}% of PnL)")
log(f"   Execution Edge:    ${execution:>10,.2f} ({abs(execution/actual)*100:.1f}% of PnL)")
log(f"\n   Avg price:  {row[4]:.4f}")
log(f"   Avg mid:    {row[5]:.4f}")
log(f"   Avg settle: {row[6]:.4f}")

# =============================================================================
# SECTION 4: OUTCOME ANALYSIS (UP vs DOWN)
# =============================================================================
log("\n" + "=" * 80)
log("4. OUTCOME ANALYSIS (UP vs DOWN)")
log("=" * 80)

r = client.query("""
    SELECT 
        outcome as outcome,
        count() as trades,
        round(sum((settle_price - price) * size), 2) as pnl,
        round(countIf((settle_price - price) * size > 0) * 100.0 / count(), 2) as win_rate,
        round(avg(price), 4) as avg_price,
        round(avg(settle_price), 4) as avg_settle
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
    GROUP BY outcome
""")
log(f"\n{'Outcome':<8} {'Trades':>8} {'PnL':>12} {'WinRate':>10} {'AvgPrice':>10} {'AvgSettle':>10}")
log("-" * 62)
for row in r.result_rows:
    log(f"{row[0]:<8} {row[1]:>8,} ${row[2]:>11,.2f} {row[3]:>9.2f}% {row[4]:>10.4f} {row[5]:>10.4f}")

# =============================================================================
# SECTION 5: TIMING ANALYSIS
# =============================================================================
log("\n" + "=" * 80)
log("5. TIMING ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        round(avg(seconds_to_end), 0),
        round(median(seconds_to_end), 0),
        min(seconds_to_end),
        max(seconds_to_end)
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
""")
row = r.result_rows[0]
log(f"\n⏱️ TIMING STATS:")
log(f"   Mean:   {row[0]:.0f}s ({row[0]/60:.1f} min)")
log(f"   Median: {row[1]:.0f}s ({row[1]/60:.1f} min)")
log(f"   Range:  {row[2]}s - {row[3]}s")

r = client.query("""
    SELECT 
        multiIf(
            seconds_to_end < 60, '< 1 min',
            seconds_to_end < 180, '1-3 min',
            seconds_to_end < 300, '3-5 min',
            seconds_to_end < 600, '5-10 min',
            seconds_to_end < 900, '10-15 min',
            seconds_to_end < 1800, '15-30 min',
            '> 30 min'
        ) as bucket,
        count() as trades,
        round(sum((settle_price - price) * size), 2) as pnl,
        round(countIf((settle_price - price) * size > 0) * 100.0 / count(), 2) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND seconds_to_end IS NOT NULL
    GROUP BY bucket
    ORDER BY 
        multiIf(bucket='< 1 min', 1, bucket='1-3 min', 2, bucket='3-5 min', 3, 
                bucket='5-10 min', 4, bucket='10-15 min', 5, bucket='15-30 min', 6, 7)
""")
log(f"\n{'Timing':<12} {'Trades':>8} {'PnL':>12} {'WinRate':>10}")
log("-" * 44)
for row in r.result_rows:
    marker = " ⭐" if row[2] > 1000 else ""
    log(f"{row[0]:<12} {row[1]:>8,} ${row[2]:>11,.2f} {row[3]:>9.2f}%{marker}")

# =============================================================================
# SECTION 6: EXECUTION ANALYSIS
# =============================================================================
log("\n" + "=" * 80)
log("6. EXECUTION ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as n,
        countIf(price < mid) as below_mid,
        countIf(price > mid) as above_mid,
        round(avg(price - mid), 4) as avg_slippage,
        round(avg(best_ask_price - best_bid_price), 4) as avg_spread
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND mid > 0
""")
row = r.result_rows[0]
n = row[0]
log(f"\n🎯 EXECUTION QUALITY ({n:,} trades):")
log(f"   Below mid (good): {row[1]:,} ({row[1]/n*100:.1f}%)")
log(f"   Above mid (bad):  {row[2]:,} ({row[2]/n*100:.1f}%)")
log(f"   Avg slippage:     {row[3]:.4f}")
log(f"   Avg spread:       {row[4]:.4f}")

r = client.query("""
    SELECT 
        exec_type,
        count() as trades,
        round(sum((settle_price - price) * size), 2) as pnl,
        round(countIf((settle_price - price) * size > 0) * 100.0 / count(), 2) as win_rate
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL AND exec_type != ''
    GROUP BY exec_type
    ORDER BY trades DESC
""")
if r.result_rows:
    log(f"\n{'Exec Type':<15} {'Trades':>8} {'PnL':>12} {'WinRate':>10}")
    log("-" * 47)
    for row in r.result_rows:
        log(f"{row[0]:<15} {row[1]:>8,} ${row[2]:>11,.2f} {row[3]:>9.2f}%")

# =============================================================================
# SECTION 7: SCENARIO COMPARISON
# =============================================================================
log("\n" + "=" * 80)
log("7. EXECUTION SCENARIO COMPARISON")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as n,
        round(sum((settle_price - price) * size), 2) as actual,
        round(sum((settle_price - mid) * size), 2) as at_mid,
        round(sum((settle_price - best_bid_price) * size), 2) as maker,
        round(sum((settle_price - best_ask_price) * size), 2) as taker
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') 
    AND settle_price IS NOT NULL AND mid > 0 
    AND best_bid_price > 0 AND best_ask_price > 0
""")
row = r.result_rows[0]
log(f"\n💹 SCENARIO COMPARISON ({row[0]:,} trades):")
log(f"   Maker (bid):  ${row[3]:>10,.2f}  ← Best case")
log(f"   Actual:       ${row[1]:>10,.2f}")
log(f"   At Mid:       ${row[2]:>10,.2f}")
log(f"   Taker (ask):  ${row[4]:>10,.2f}  ← Worst case")
if row[1] != 0:
    log(f"\n   Maker improvement: {row[3]/row[1]:.1f}x")

# =============================================================================
# SECTION 8: ORDER BOOK DEPTH ANALYSIS
# =============================================================================
log("\n" + "=" * 80)
log("8. ORDER BOOK DEPTH ANALYSIS")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total,
        countIf(length(bid_levels) > 0) as with_depth,
        round(avgIf(total_bid_volume, length(bid_levels) > 0), 2) as avg_bid_vol,
        round(avgIf(total_ask_volume, length(bid_levels) > 0), 2) as avg_ask_vol,
        round(avgIf(book_imbalance, length(bid_levels) > 0), 4) as avg_imbalance
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')
""")
row = r.result_rows[0]
log(f"\n📚 DEPTH DATA ({row[1]:,} / {row[0]:,} trades = {row[1]/row[0]*100:.1f}%):")
if row[1] > 0:
    log(f"   Avg bid volume:     ${row[2]:,.2f}")
    log(f"   Avg ask volume:     ${row[3]:,.2f}")
    log(f"   Avg book imbalance: {row[4]:.4f}")

# =============================================================================
# SECTION 9: WIN/LOSS STATISTICS
# =============================================================================
log("\n" + "=" * 80)
log("9. WIN/LOSS STATISTICS")
log("=" * 80)

r = client.query("""
    SELECT 
        count() as total,
        countIf((settle_price - price) * size > 0) as winners,
        countIf((settle_price - price) * size < 0) as losers,
        round(sumIf((settle_price - price) * size, (settle_price - price) * size > 0), 2) as win_pnl,
        round(sumIf((settle_price - price) * size, (settle_price - price) * size < 0), 2) as loss_pnl,
        round(avgIf((settle_price - price) * size, (settle_price - price) * size > 0), 2) as avg_win,
        round(avgIf(abs((settle_price - price) * size), (settle_price - price) * size < 0), 2) as avg_loss
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
""")
row = r.result_rows[0]
total, winners, losers = row[0], row[1], row[2]
win_pnl, loss_pnl = row[3], row[4]
avg_win, avg_loss = row[5], row[6]
log(f"\n🎯 WIN/LOSS BREAKDOWN:")
log(f"   Total trades:   {total:,}")
log(f"   Winners:        {winners:,} ({winners/total*100:.1f}%)")
log(f"   Losers:         {losers:,} ({losers/total*100:.1f}%)")
log(f"   Win PnL:        ${win_pnl:,.2f}")
log(f"   Loss PnL:       ${loss_pnl:,.2f}")
log(f"   Avg Win:        ${avg_win:.2f}")
log(f"   Avg Loss:       ${avg_loss:.2f}")
log(f"   Profit Factor:  {abs(win_pnl/loss_pnl):.2f}")
log(f"   Win/Loss Ratio: {avg_win/avg_loss:.2f}")

# =============================================================================
# SECTION 10: MONTE CARLO SIMULATION
# =============================================================================
log("\n" + "=" * 80)
log("10. MONTE CARLO SIMULATION (20,000 iterations)")
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
        'p25': np.percentile(totals, 25),
        'p50': np.percentile(totals, 50),
        'p75': np.percentile(totals, 75),
        'p95': np.percentile(totals, 95),
        'sharpe': (np.mean(pnl) / np.std(pnl)) * np.sqrt(252*24) if np.std(pnl) > 0 else 0,
        'dd_p50': np.percentile(max_dds, 50),
        'dd_p95': np.percentile(max_dds, 95)
    }

log(f"\n{'Scenario':<10} {'5th%':>10} {'25th%':>10} {'Median':>10} {'75th%':>10} {'95th%':>10} {'Sharpe':>8}")
log("-" * 72)
for s in ['actual', 'mid', 'maker', 'taker']:
    r = bootstrap(df[f'pnl_{s}'].values)
    log(f"{s.capitalize():<10} ${r['p05']:>9,.0f} ${r['p25']:>9,.0f} ${r['p50']:>9,.0f} ${r['p75']:>9,.0f} ${r['p95']:>9,.0f} {r['sharpe']:>8.2f}")

log(f"\n📉 MAX DRAWDOWN:")
log(f"{'Scenario':<10} {'Median DD':>12} {'95th% DD':>12}")
log("-" * 36)
for s in ['actual', 'maker']:
    r = bootstrap(df[f'pnl_{s}'].values)
    log(f"{s.capitalize():<10} ${r['dd_p50']:>11,.0f} ${r['dd_p95']:>11,.0f}")

# =============================================================================
# SECTION 11: MARKET-SPECIFIC MONTE CARLO
# =============================================================================
log("\n" + "=" * 80)
log("11. MARKET-SPECIFIC MONTE CARLO")
log("=" * 80)

df_full = client.query_df("""
    SELECT 
        market_slug as market_slug,
        price, size, settle_price
    FROM user_trade_enriched_v2
    WHERE username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER') AND settle_price IS NOT NULL
""")

def market_type(slug):
    if 'btc-updown-15m' in str(slug): return '15min-BTC'
    elif 'eth-updown-15m' in str(slug): return '15min-ETH'
    elif 'bitcoin-up-or-down' in str(slug): return '1hour-BTC'
    elif 'ethereum-up-or-down' in str(slug): return '1hour-ETH'
    return 'other'

df_full['mtype'] = df_full['market_slug'].apply(market_type)
df_full['pnl'] = (df_full['settle_price'] - df_full['price']) * df_full['size']

log(f"\n{'Market':<12} {'5th%':>10} {'Median':>10} {'95th%':>10} {'Sharpe':>8}")
log("-" * 52)
for mtype in ['15min-BTC', '15min-ETH', '1hour-BTC', '1hour-ETH']:
    subset = df_full[df_full['mtype'] == mtype]['pnl'].dropna().values
    if len(subset) > 100:
        r = bootstrap(subset, iters=5000)
        log(f"{mtype:<12} ${r['p05']:>9,.0f} ${r['p50']:>9,.0f} ${r['p95']:>9,.0f} {r['sharpe']:>8.2f}")

# =============================================================================
# FINAL SUMMARY
# =============================================================================
log("\n" + "=" * 80)
log("FINAL STRATEGY SUMMARY")
log("=" * 80)

log("""
┌─────────────────────────────────────────────────────────────────────────────┐
│                    GABAGOOL22 REVERSE-ENGINEERED STRATEGY                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  MARKETS:     Bitcoin + Ethereum, 15min + 1hour Up/Down                     │
│  FOCUS:       15min-BTC (best performer)                                    │
│  TIMING:      Enter 10-15 min before resolution                             │
│  DIRECTION:   Favor DOWN outcomes (~56% vs ~47% win rate)                   │
│  EXECUTION:   MAKER orders at bid+1 tick                                    │
│  SIZING:      $10-20 per trade                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│  KEY INSIGHT: ~90% of PnL comes from EXECUTION EDGE, not direction          │
└─────────────────────────────────────────────────────────────────────────────┘
""")

log("\n✅ DEEP ANALYSIS COMPLETE")
out.close()
