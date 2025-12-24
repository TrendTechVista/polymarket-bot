# TARGET_USER Strategy Reverse-Engineering — Addendum (Data Limits + Sizing + Portfolio)

This addendum summarizes what the current dataset can/can’t support for “exact” strategy matching, and lays out a concrete path to get to a high-fidelity replica as you keep collecting data.

## Update (Frozen Snapshot 2025-12-19)

Using `research/data/snapshots/TARGET_USER-20251219T215124+0000/`:

- Trades: **44,093** (**100% BUY**), resolved: **39,864**
- Notional: **~$324,967** (sum of `price * size`)
- Realized PnL (resolved, fee-excluded): **~$6,898**
- Market universe is still exactly BTC/ETH Up/Down:
  - `btc-updown-15m-*`, `eth-updown-15m-*`
  - `bitcoin-up-or-down-*`, `ethereum-up-or-down-*`
- Outcome balance is roughly even (sample-dependent):
  - Trades: `Up`=22,550, `Down`=21,543
  - Resolved realized PnL: `Up`=-$4,276.66, `Down`=+$11,174.84 (directional split is expected in a regime; net is what matters)
- Paired UP/DOWN behavior is extremely strong:
  - **707 / 717** conditions have both outcomes (**98.6%**)
  - Nearest opposite-leg timing (same condition): p50 **~10s**, p90 **~66s**; **~88.9%** within **60s**
  - Pair-size imbalance is non-zero but typically modest: `Up/Down` total size ratio p50 **~1.07** (p90 **~2.19**)
- Time-to-end sizing is **series-specific** (medians):
  - **BTC 15m**: `<1m=11`, `1-3m=13`, `3-5m=17`, `5-10m=19`, `10-15m=20`
  - **ETH 15m**: `<1m=8`, `1-3m=10`, `3-5m=12`, `5-10m=13`, `10-15m=14`
  - **BTC 1h**: `<1m=9`, `1-3m=10`, `3-5m=11`, `5-10m=12`, `10-15m=14`, `15-20m=15`, `20-30m=17`, `30-60m=18`
  - **ETH 1h**: `<1m=7`, `1-5m=8`, `5-10m=9`, `10-15m=11`, `15-20m=12`, `20-30m=13`, `30-60m=14`
- Complete-set edge from `clob_tob` rows where both outcomes are present for a `trade_key`:
  - `edge_bid = 1 - (bid_up + bid_down)` median **~0.01**, p90 **~0.02**
  - `edge_bid_ask` (one leg crosses): median **~0.00** (break-even-ish)
  - `edge_ask = 1 - (ask_up + ask_down)` median **~-0.01**
- Data quality is still the main limiter for “exact clone”:
  - `clob_tob` (trade-triggered) lag remains high: p50 **~62s**, p90 **~101s**
  - Only **~47%** of `trade_key`s in `clob_tob` have **both** outcomes captured

Tooling:
- Run an offline report anytime with `research/snapshot_report.py`.

Implementation status (as of this repo state):
- `strategy-service/src/main/java/com/polybot/hft/polymarket/strategy/GabagoolDirectionalEngine.java` now:
  - Uses **discrete series-aware sizing** (matches the medians above)
  - Adds **FAST_TOP_UP** (lagging-leg taker top-up after a recent fill) to better match the observed pairing timing
    - FAST_TOP_UP is now configurable independently of end-of-market top-ups via `complete-set-fast-top-up-min-shares`
  - Bootstraps **per-market inventory** from Polymarket **positions snapshots** (so skew/top-up/exposure caps behave correctly after restarts)
  - Keeps **order-status polling enabled** in paper/sim runs (so fills are observed and inventory updates in real time)
  - Gates quoting on **planned quote prices** (not just displayed bids)
  - Cancels/replace existing orders before placing a top-up (prevents hidden overexposure)
- `strategy-service/src/main/resources/application-develop.yaml` disables symmetric taker mode (maker + top-up only).

## Current ClickHouse Snapshot (As Of 2025-12-17)

High-level state from the live ClickHouse tables (not a frozen Parquet snapshot):

- Trades: **~26.9k** total, **~23.3k** resolved
- Notional: **~$199k** (sum of `price * size`)
- Realized PnL (resolved, fee-excluded): **~$2.4k**
- TOB freshness is still poor (from `polybot.user_trade_research`, `tob_known=1`):
  - `tob_lag_millis` median **~62.7s**, p90 **~101s**, max **~301s**
- WS TOB is now live for the active Up/Down token universe:
  - `polybot.market_ws_tob` + `polybot.user_trade_enriched_v3` (ASOF join)
  - When WS is running, `ws_tob_lag_millis` is typically **~100–300ms**
  - Important: Polymarket’s WS subscription does **not** reliably apply “re-subscribe with a new asset list” on an
    existing connection; the client must reconnect when the asset set changes. This is now handled in
    `polybot-core` (`ClobMarketWebSocketClient`).
- Polygon receipts are now being collected:
  - `polybot.polygon_tx_receipts` + `polybot.user_trade_enriched_v2.tx_*` join
  - Important: `tx.from` is a relayer (not the trader). The trader/proxy address appears inside receipt logs.
  - New decoded views (from raw receipts):
    - `polybot.polygon_erc20_transfers`, `polybot.polygon_usdc_transfers`
    - `polybot.polygon_exchange_order_filled`, `polybot.polygon_exchange_orders_matched`
    - `polybot.user_trade_onchain_pair` (joins fills ↔ on-chain and surfaces the paired leg when present)
- Up/Down pairing signal is strong:
  - `polybot.user_complete_sets_detected` pairs opposing outcomes within **60s** (diagnostic / may overcount).
- Dual-outcome TOB capture is improving but incomplete:
  - last ~2h: **~19%** of `trade_key`s have **both** token snapshots in `polybot.clob_tob`

Interpretation:
- Any “exact” reconstruction is bottlenecked by **decision-time book state** and **order lifecycle** (see sections below).
- The fills strongly suggest **paired / hedged behavior** (often compatible with complete-set-style execution), so a purely one-shot directional model is incomplete.

## Frozen Snapshot Used (For Older Numbers Below)

- Latest feature snapshot: `research/data/snapshots/TARGET_USER-20251216T171416+0000/features.parquet`
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

### 1.5 On-chain evidence: “complete-set-like” matching is a major driver (high signal)

Using `polybot.user_trade_onchain_pair` (1,185 trades with on-chain `OrdersMatched` logs):

- **62.7%** of on-chain-matched trades have a paired opposite-outcome fill in the same tx (`pair_token_id != ''`)
- On the resolved subset (931 trades), PnL splits sharply:
  - `completeSetLike=1`: **+$220.20** (avg **+$0.380**/trade, win-rate **~52.7%**)
  - `completeSetLike=0`: **-$154.90** (avg **-$0.440**/trade, win-rate **~45.5%**)

This strongly suggests the “exact replica” must model **how fills route** (paired outcome vs collateral leg), not only
the visible trade prints. A fast way to monitor this live is:

`python research/onchain_match_report.py --username TARGET_USER`

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
- `ingestor-service` now discovers the active BTC/ETH Up/Down 15m + 1h markets and subscribes them to the **market websocket**, producing continuous `market_ws.tob` events.
- `ingestor-service` can now fetch **Polygon receipts** per trade `transactionHash` (`polygon.tx.receipt` events).
- ClickHouse views updated to safely support **multiple TOB rows per trade key** by keying TOB-by-trade on `(trade_key, token_id)` and joining with both.

Files:
- `ingestor-service/src/main/java/com/polybot/ingestor/ingest/PolymarketMarketContextIngestor.java`
- `ingestor-service/src/main/java/com/polybot/ingestor/ingest/PolymarketUpDownMarketWsIngestor.java`
- `ingestor-service/src/main/java/com/polybot/ingestor/ingest/PolygonTxReceiptIngestor.java`
- `analytics-service/clickhouse/init/003_enriched.sql`
- `analytics-service/clickhouse/init/008_enhanced_data_collection.sql`
- `analytics-service/clickhouse/init/0081_market_ws_tob.sql`
- `analytics-service/clickhouse/init/0080_polygon_tx_receipts.sql`
- `analytics-service/clickhouse/init/0082_polygon_log_decoding.sql`
- `analytics-service/clickhouse/init/0090_enriched_ws.sql`

Important:
- Re-apply ClickHouse DDL before your next data-collection run: `scripts/clickhouse/apply-init.sh`

### 3.2 Next data upgrades (highest ROI)

1) **Use the on-chain decoded views to label fills** (complete-set-like vs not):
   - `polybot.user_trade_onchain_pair` is now available and shows a strong PnL split by routing type.
   - Next step: join this label into the main feature dataset so we can learn which market states produce
     complete-set-like fills (and replicate that routing behavior live).

2) **Outcome-pair features**:
   - At each decision time bucket, build a single row containing both outcomes’ prices/sizes/imbalance/spread.
   - This is the minimum requirement for direction-choice identification.

3) **Order lifecycle for your own bot (fills/cancels)**:
   - For your live bot, log order placements, cancels, and fills so the sizing/execution model can be calibrated properly.
   - Without cancels/unfilled quotes, “exact” replication remains underdetermined even with perfect TOB.

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

## 6) IMPORTANT: Sample-Period Bias Warning (2025-12-18 Update)

### The DOWN Profitability Fallacy

Initial research (Dec 14-18, 2025) showed:
- DOWN trades: **+$11,932 PnL** (profitable)
- UP trades: **-$6,790 PnL** (losing)

This was **incorrectly interpreted** as TARGET_USER having a directional bias toward DOWN.

### Root Cause: BTC Was Trending Down

During our observation period (Dec 14-18), Bitcoin dropped from ~$90,257 to ~$85,500 (**~5% decline**).
Source: [CoinDesk](https://www.coindesk.com/markets/2025/12/15/bitcoin-plunges-below-usd87k-as-crypto-weakness-persists-ether-below-usd3k/)

This means:
- DOWN outcomes were **more likely to resolve in-the-money** during this period
- The DOWN profitability was **sample-period dependent**, not a strategy signal
- In an up-trending market, we would expect the opposite pattern

### Correct Understanding: Market-Neutral Arbitrage

Gabagool22's strategy is **market-neutral complete-set arbitrage**:
- The edge comes from `1.0 - (bid_UP + bid_DOWN)` when this is positive
- He doesn't predict direction; he profits from Polymarket pricing inefficiencies
- Both UP and DOWN are quoted with **equal size** (adjusted only for inventory skew)

### Implementation Change

Directional-bias logic was removed from the codebase. The strategy is strictly market-neutral: it quotes UP and DOWN with equal base sizing (adjusted only by inventory skew / top-up mechanics).

### Data Collection Recommendation

To validate any future directional signals, collect data across:
1. Up-trending crypto periods (BTC rising)
2. Down-trending crypto periods (BTC falling)
3. Sideways/choppy periods

Only if a directional pattern persists **across all regimes** should it be considered a real signal.

## 7) What to do next (so we can converge to "exact")

1) Re-run ingestors with the new "both outcomes TOB" capture.
2) Rebuild feature dataset to produce paired Up/Down state per decision time.
3) Re-fit:
   - Maker/taker decision + lead→lag pairing latency model
   - Execution model (fill probability vs quoted price distance to mid, by series)
4) Only then attempt "exact match" and/or improvements (MPT + sizing).
5) **Collect data across different market regimes** to confirm the market-neutral assumptions hold.
