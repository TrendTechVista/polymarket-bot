#!/usr/bin/env python3
"""
Final verification of gabagool22 strategy - December 16, 2025
"""

import clickhouse_connect
from datetime import datetime

print("=" * 80)
print("GABAGOOL22 STRATEGY VERIFICATION")
print(f"Timestamp: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
print("=" * 80)

client = clickhouse_connect.get_client(host='localhost', port=8123, database='polybot')

tables = set(r[0] for r in client.query("SHOW TABLES").result_rows)
if "user_trade_enriched_v4" in tables:
    view = "user_trade_enriched_v4"
    has_ws = True
elif "user_trade_enriched_v3" in tables:
    view = "user_trade_enriched_v3"
    has_ws = True
else:
    view = "user_trade_enriched_v2"
    has_ws = False

mid_expr = "coalesce(ws_mid, mid)" if has_ws else "mid"
bid_expr = "coalesce(ws_best_bid_price, best_bid_price)" if has_ws else "best_bid_price"
ask_expr = "coalesce(ws_best_ask_price, best_ask_price)" if has_ws else "best_ask_price"
exec_col = "ws_exec_type" if has_ws else "exec_type"

print(f"Using view: {view} (exec={exec_col})")

# 1. Total data overview
print("\n" + "=" * 60)
print("1. DATA OVERVIEW")
print("=" * 60)

r = client.query(f"""
    SELECT 
        count() as total,
        countIf(settle_price IS NOT NULL) as resolved,
        round(sum(size * price), 2) as volume,
        min(ts) as first_ts,
        max(ts) as last_ts
    FROM {view}
    WHERE username = 'gabagool22'
""")
row = r.result_rows[0]
print(f"Total trades:  {row[0]:,}")
print(f"Resolved:      {row[1]:,}")
print(f"Volume:        ${row[2]:,.2f}")
print(f"Time range:    {row[3]} to {row[4]}")

# 2. Market breakdown with outcome
print("\n" + "=" * 60)
print("2. MARKET + OUTCOME BREAKDOWN")
print("=" * 60)

r = client.query(f"""
    SELECT 
        multiIf(
            market_slug LIKE 'btc-updown-15m-%', '15min-BTC',
            market_slug LIKE 'eth-updown-15m-%', '15min-ETH',
            market_slug LIKE 'bitcoin-up-or-down-%', '1hour-BTC',
            market_slug LIKE 'ethereum-up-or-down-%', '1hour-ETH',
            'other'
        ) as mtype,
        lower(outcome) as outcome,
        count() as trades,
        round(sumIf((settle_price - price) * size, settle_price IS NOT NULL), 2) as pnl,
        round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0) * 100.0 / 
              nullIf(countIf(settle_price IS NOT NULL), 0), 2) as win_rate
    FROM {view}
    WHERE username = 'gabagool22'
    GROUP BY mtype, outcome
    ORDER BY mtype, outcome
""")

print(f"{'Market':<12} {'Outcome':<8} {'Trades':>8} {'PnL':>12} {'WinRate':>8}")
print("-" * 52)
for row in r.result_rows:
    print(f"{row[0]:<12} {row[1]:<8} {row[2]:>8} ${row[3]:>10,.2f} {row[4]:>7.2f}%")

# 3. Timing windows for 1-hour markets
print("\n" + "=" * 60)
print("3. TIMING ANALYSIS BY MARKET TYPE")
print("=" * 60)

for mtype in ['15min-BTC', '15min-ETH', '1hour-BTC', '1hour-ETH']:
    like_pattern = {
        '15min-BTC': 'btc-updown-15m-%',
        '15min-ETH': 'eth-updown-15m-%',
        '1hour-BTC': 'bitcoin-up-or-down-%',
        '1hour-ETH': 'ethereum-up-or-down-%'
    }[mtype]

    r = client.query(f"""
        SELECT 
            multiIf(
                seconds_to_end < 60, '< 1 min',
                seconds_to_end < 180, '1-3 min',
                seconds_to_end < 300, '3-5 min',
                seconds_to_end < 600, '5-10 min',
                seconds_to_end < 900, '10-15 min',
                seconds_to_end < 1800, '15-30 min',
                seconds_to_end < 3600, '30-60 min',
                '> 60 min'
            ) as bucket,
            count() as trades,
            round(sumIf((settle_price - price) * size, settle_price IS NOT NULL), 2) as pnl,
            round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0) * 100.0 / 
                  nullIf(countIf(settle_price IS NOT NULL), 0), 2) as win_rate
        FROM {view}
        WHERE username = 'gabagool22'
          AND market_slug LIKE '{like_pattern}'
          AND seconds_to_end IS NOT NULL
        GROUP BY bucket
        ORDER BY 
            CASE bucket
                WHEN '< 1 min' THEN 1
                WHEN '1-3 min' THEN 2
                WHEN '3-5 min' THEN 3
                WHEN '5-10 min' THEN 4
                WHEN '10-15 min' THEN 5
                WHEN '15-30 min' THEN 6
                WHEN '30-60 min' THEN 7
                ELSE 8
            END
    """)

    print(f"\n{mtype}:")
    print(f"  {'Bucket':<12} {'Trades':>8} {'PnL':>12} {'WinRate':>8}")
    print("  " + "-" * 44)
    for row in r.result_rows:
        marker = " ⭐" if row[2] > 100 else ""
        print(f"  {row[0]:<12} {row[1]:>8} ${row[2]:>10,.2f} {row[3]:>7.2f}%{marker}")

# 4. DOWN vs UP by market
print("\n" + "=" * 60)
print("4. DOWN vs UP WIN RATE BY MARKET")
print("=" * 60)

r = client.query(f"""
    SELECT 
        multiIf(
            market_slug LIKE 'btc-updown-15m-%', '15min-BTC',
            market_slug LIKE 'eth-updown-15m-%', '15min-ETH',
            market_slug LIKE 'bitcoin-up-or-down-%', '1hour-BTC',
            market_slug LIKE 'ethereum-up-or-down-%', '1hour-ETH',
            'other'
        ) as mtype,
        round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0 AND lower(outcome) = 'down') * 100.0 / 
              nullIf(countIf(settle_price IS NOT NULL AND lower(outcome) = 'down'), 0), 2) as down_win_rate,
        round(countIf(settle_price IS NOT NULL AND (settle_price - price) * size > 0 AND lower(outcome) = 'up') * 100.0 / 
              nullIf(countIf(settle_price IS NOT NULL AND lower(outcome) = 'up'), 0), 2) as up_win_rate,
        round(sumIf((settle_price - price) * size, settle_price IS NOT NULL AND lower(outcome) = 'down'), 2) as down_pnl,
        round(sumIf((settle_price - price) * size, settle_price IS NOT NULL AND lower(outcome) = 'up'), 2) as up_pnl
    FROM {view}
    WHERE username = 'gabagool22'
    GROUP BY mtype
    ORDER BY down_pnl DESC
""")

print(f"{'Market':<12} {'DOWN WR':>10} {'UP WR':>10} {'DOWN PnL':>12} {'UP PnL':>12}")
print("-" * 60)
for row in r.result_rows:
    print(f"{row[0]:<12} {row[1]:>9.2f}% {row[2]:>9.2f}% ${row[3]:>10,.2f} ${row[4]:>10,.2f}")

# 5. Execution quality
print("\n" + "=" * 60)
print("5. EXECUTION ANALYSIS")
print("=" * 60)

r = client.query(f"""
    SELECT 
        count() as trades_with_mid,
        round(sumIf((settle_price - price) * size, settle_price IS NOT NULL), 2) as actual_pnl,
        round(sumIf((settle_price - {mid_expr}) * size, settle_price IS NOT NULL AND {mid_expr} > 0), 2) as mid_pnl,
        round(sumIf((settle_price - {bid_expr}) * size, settle_price IS NOT NULL AND {bid_expr} > 0), 2) as maker_pnl,
        round(sumIf((settle_price - {ask_expr}) * size, settle_price IS NOT NULL AND {ask_expr} > 0), 2) as taker_pnl
    FROM {view}
    WHERE username = 'gabagool22'
      AND {mid_expr} > 0
""")
row = r.result_rows[0]
print(f"Trades with TOB:  {row[0]:,}")
print(f"Actual PnL:       ${row[1]:,.2f}")
print(f"At Mid PnL:       ${row[2]:,.2f}")
print(f"Maker (bid) PnL:  ${row[3]:,.2f}")
print(f"Taker (ask) PnL:  ${row[4]:,.2f}")
print(f"\nMaker improvement: {row[3]/row[1]:.1f}x over actual")

# 6. Final recommendation
print("\n" + "=" * 60)
print("6. FINAL STRATEGY RECOMMENDATION")
print("=" * 60)

print("""
Observed from fills + (often stale) TOB snapshots:
- Core universe is BTC/ETH Up/Down in 15m + 1h series
- Edge is dominated by execution quality (paying below mid / maker-like fills)
- Timing effects exist, but differ by series (15m: 10-15m bucket; 1h: 15-60m buckets)

Not provable from public fills alone:
- Exact order placement/cancel logic (we only see filled trades)
- Fee tier / maker rebates (not included in PnL here)
- True decision-time book state (TOB lag is large in this dataset)

Recommendation:
- Treat this report as a descriptive snapshot, not a deploy-ready “exact clone”.
- Use `research/run_simulation.py` for Monte Carlo and `docs/GABAGOOL22_STRATEGY_RESEARCH_ADDENDUM.md` for the data-limit checklist.
""")
