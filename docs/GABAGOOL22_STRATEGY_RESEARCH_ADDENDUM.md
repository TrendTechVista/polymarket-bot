# gabagool22 Strategy Reverse-Engineering — Addendum (Data Limits + Sizing + Portfolio)

This addendum summarizes what the current dataset can/can’t support for “exact” strategy matching, and lays out a concrete path to get to a high-fidelity replica as you keep collecting data.

## Current ClickHouse Snapshot (As Of 2025-12-17)

High-level state from the live ClickHouse tables (not a frozen Parquet snapshot):

- Trades: **~24.8k** total, **~21.6k** resolved
- Notional: **~$183k** (sum of `price * size`)
- Realized PnL (resolved, fee-excluded): **~$2,964**
- TOB freshness is still poor (from `polybot.user_trade_research`, `tob_known=1`):
  - `tob_lag_millis` median **~62.7s**, p90 **~101s**, max **~301s**
- Up/Down pairing signal is strong:
  - **~82%** of Up/Down trades can be matched to the opposite outcome in the same market within **60s**
- Dual-outcome TOB capture is improving but incomplete:
  - last ~2h: **~19%** of `trade_key`s have **both** token snapshots in `polybot.clob_tob`

Interpretation:
- Any “exact” reconstruction is bottlenecked by **decision-time book state** and **order lifecycle** (see sections below).
- The fills strongly suggest **paired / hedged behavior** (often compatible with complete-set-style execution), so a purely one-shot directional model is incomplete.

## Frozen Snapshot Used (For Older Numbers Below)

- Latest feature snapshot: `research/data/snapshots/gabagool22-20251216T171416+0000/features.parquet`
- Resolved trades: **15,573**
- Total realized PnL (resolved): **$2,057.87**

## 1) Strategy Signatures We Can Confirm (High Confidence)

### 1.1 Two distinct regimes

The feature layer labels two regimes:

| Regime | Resolved trades | PnL |
|---|---:|---:|
| `DIRECTIONAL` | 10,265 | $1,706.50 |
| `COMPLETE_SET_ARBITRAGE` | 5,308 | $351.36 |

Interpretation:
- There is a meaningful amount of **complete-set behavior**, not just pure directional betting.
- A “gabagool clone” that only does one-off directional entries is incomplete.

### 1.2 “Execution edge” dominates when TOB is reliable

On the subset with usable top-of-book (`tob_known=1` and `mid/bid/ask > 0`), we can decompose PnL:

- Trades in TOB-known subset: **13,368**
- Actual PnL: **$1,115.67**
- Directional alpha (`(settle - mid) * size`): **$73.99**
- Execution edge (`(mid - price) * size`): **$1,041.68**

Interpretation:
- When we have a meaningful “fair price” proxy (mid), the dominant driver is **paying below mid** (or equivalently: capturing spread / liquidity provision).
- Any live bot that crosses the spread, fails to get fills near mid, or has stale market data will likely fail even if the “direction signal” is decent.

### 1.3 Strong asymmetry: DOWN profitable, UP negative (in this sample)

Within the `DIRECTIONAL` regime:

| Outcome | Trades | PnL | Mean PnL/trade |
|---|---:|---:|---:|
| Down | 5,095 | $5,407.29 | $1.06 |
| Up | 5,170 | -$3,700.79 | -$0.72 |

Interpretation:
- A static “DOWN bias” helps match this dataset, but it may be **sample-period dependent**.
- If your live period is structurally different (up-only drift, different liquidity), this is a classic place where an offline replica fails live.

### 1.4 Sizing is not constant across market series

Median **share size** by market family (resolved trades):

| Series | Median shares |
|---|---:|
| `btc-updown-15m-*` | 19–20 |
| `eth-updown-15m-*` | 14 |
| `bitcoin-up-or-down-*` | 17 |
| `ethereum-up-or-down-*` | 13 |

Implication:
- Matching “volume profile” requires **series-aware sizing** (or at least weights).

## 2) Why “100% exact match” is not currently provable

### 2.1 Top-of-book at decision time is often stale

In TOB-known subset, `tob_lag_millis` (trade timestamp → captured TOB timestamp):

- Median: **62,561 ms (~62s)**
- P90: **99,712 ms (~100s)**
- Max: **300,939 ms (~301s)**

This lag is large enough that “best bid/ask at trade time” is frequently not the actual state gabagool traded against.

### 2.2 Missing “both sides” market state at the moment of choosing direction

Each trade row naturally contains features for the **token that traded**. But direction choice requires comparing **Up vs Down simultaneously** (prices + sizes + microstructure). Without that, direction-prediction models are underdetermined.

### 2.3 Trades don’t reveal unfilled/cancelled orders

If gabagool posts multiple maker orders and only one side fills, we never observe the unfilled side. From fills alone, it can look “random”, even if the true strategy is symmetric quoting with a slight bias.

## 3) Data Collection Changes to Enable High-Fidelity Reverse Engineering

### 3.1 Implemented in code (ready for your next run)

- `ingestor-service` now snapshots **all outcomes in the same market** (when Gamma token lists are available), not only the traded token.
- ClickHouse views updated to safely support **multiple TOB rows per trade key** by keying TOB-by-trade on `(trade_key, token_id)` and joining with both.

Files:
- `ingestor-service/src/main/java/com/polybot/ingestor/ingest/PolymarketMarketContextIngestor.java`
- `analytics-service/clickhouse/init/003_enriched.sql`
- `analytics-service/clickhouse/init/008_enhanced_data_collection.sql`

Important:
- Re-apply ClickHouse DDL before your next data-collection run: `scripts/clickhouse/apply-init.sh`

### 3.2 Next data upgrades (highest ROI)

1) **Continuous WS TOB store** (not “TOB on trade arrival”):
   - Record market WS TOB snapshots continuously for the relevant token universe.
   - ASOF join those snapshots to the trade timestamp during feature generation.
   - Goal: push effective TOB lag from ~60s → <1s.

2) **Outcome-pair features**:
   - At each decision time bucket, build a single row containing both outcomes’ prices/sizes/imbalance/spread.
   - This is the minimum requirement for direction-choice identification.

3) **Order lifecycle for your own bot (fills/cancels)**:
   - For your live bot, log order placements, cancels, and fills so the sizing/execution model can be calibrated properly.

## 4) Bet Sizing for a Smaller Bankroll (Practical + Conservative)

The core trade is a binary payoff; if you buy at price `q` with estimated win probability `p`, the classical Kelly fraction is:

`f* = (p - q) / (1 - q)`

For market-making style entries, a conservative proxy is:
- `p ≈ mid`
- `q ≈ your entry price`
- edge ≈ `mid - entry`

Practical constraints matter more than theory here:
- Use **fractional Kelly** (e.g., 5–20% of Kelly) because of adverse selection and model error.
- Hard-cap exposure (per-order and total) to avoid death-by-variance in binary markets.

Implementation support (strategy-service):
- `hft.strategy.gabagool.quote-size` remains a fixed USDC notional target.
- Optional bankroll-aware sizing knobs:
  - `bankroll-usd`
  - `quote-size-bankroll-fraction` (scales order notional with bankroll)
  - `max-order-bankroll-fraction` (cap)
  - `max-total-bankroll-fraction` (cap)

## 5) Portfolio Construction (Modern Portfolio Theory applied realistically)

In practice, the “assets” are not individual markets; they are *strategy legs*:
- Series buckets (BTC-15m, ETH-15m, BTC-1h, ETH-1h)
- Regimes (directional vs complete-set)

Recommended workflow:
1) Convert trade-level outcomes into **bucket-level returns** (PnL / notional) per series.
2) Estimate expected returns + covariance with **shrinkage** (sample is short; correlations are unstable).
3) Use a simple allocator:
   - Risk-parity across series as a baseline, then tilt toward higher estimated Sharpe.
   - Keep strict caps (because binary markets have fat tails and clustered liquidity events).

## 6) What to do next (so we can converge to “exact”)

1) Re-run ingestors with the new “both outcomes TOB” capture.
2) Rebuild feature dataset to produce paired Up/Down state per decision time.
3) Re-fit:
   - Direction-choice model (now identifiable)
   - Execution model (fill probability vs quoted price distance to mid, by series)
4) Only then attempt “exact match” and/or improvements (MPT + sizing).
