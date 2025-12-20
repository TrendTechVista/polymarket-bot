# Gabagool22 Clone — Current Strategy Spec (as implemented)

This document describes the **current** implemented “gabagool22 clone” logic in this repo, based on:
- `strategy-service/src/main/java/com/polybot/hft/polymarket/strategy/GabagoolDirectionalEngine.java`
- `strategy-service/src/main/resources/application-develop.yaml`
- `executor-service/src/main/java/com/polybot/hft/executor/sim/PaperExchangeSimulator.java`

The goal is to give you a clean spec you can hand to another LLM to build a minimal Python bot for testing.

## 1) Market Universe

Only **Up/Down** binary markets for:
- **BTC 15m** (`market_slug` starts with `btc-updown-15m-`)
- **ETH 15m** (`market_slug` starts with `eth-updown-15m-`)
- **BTC 1h** (`market_slug` starts with `bitcoin-up-or-down-`)
- **ETH 1h** (`market_slug` starts with `ethereum-up-or-down-`)

The strategy runs on each market instance from creation until expiry (with configured time-window caps below).

## 2) Required Data (Decision-Time)

Per market instance you must have **both legs’** WS top-of-book:
- `bestBidPrice`, `bestAskPrice`, sizes (optional for logic), and `updatedAt`.
- Treat TOB as **stale** if `now - updatedAt > 2000ms`.
- If either leg is missing/stale: **cancel** any working order(s) on that token and do nothing for that market tick.

## 3) Timing / Looping

Each strategy tick (default `refresh-millis: 500`):
1. Refresh positions snapshot every ~5s (used for bankroll/exposure + inventory rehydration).
2. Evaluate each active market instance.
3. Poll open orders at most every 1s; cancel any order older than 300s.

## 4) Time-to-End Window

Compute `seconds_to_end = (market_end_time - now).seconds`.

Hard lifetime by market type:
- 15m: trade only if `0 <= seconds_to_end <= 900`
- 1h: trade only if `0 <= seconds_to_end <= 3600`

Config window (clamped to the lifetime above):
- Trade only if `min-seconds-to-end <= seconds_to_end <= max-seconds-to-end`

Outside window: cancel working orders for that market.

## 5) Core Signal: Complete-Set Mispricing Edge

Let:
- `bid_up  = bestBid(UP)`
- `bid_dn  = bestBid(DOWN)`

Define complete-set cost and edge:
- `complete_set_cost = bid_up + bid_dn`
- `complete_set_edge = 1.0 - complete_set_cost`

Trade only if:
- `complete_set_edge >= complete-set-min-edge` (default `0.01`)

If edge is below threshold: cancel working orders for that market.

## 6) Pricing (Maker Mode)

Maker mode is the default (taker-entry mode exists but is disabled by default in config).

For each leg (UP and DOWN), compute a maker entry price:
1. Read `bestBid`, `bestAsk`. If missing, skip that leg.
2. Compute `mid = (bestBid + bestAsk) / 2`.
3. Compute `spread = bestAsk - bestBid`.
4. Compute `effectiveImproveTicks = improve-ticks + skewTicks` (skew described below).
5. If spread is extremely wide (`spread >= 0.20`), quote near mid to avoid never-filling “0.01 bid” traps:
   - `entry = mid - tickSize * max(0, improve-ticks - skewTicks)`
6. Else (normal tight spread), quote:
   - `entry = min(bestBid + tickSize * effectiveImproveTicks, mid)`
7. Round **down** to tick.
8. Never cross: if `entry >= bestAsk`, set `entry = bestAsk - tickSize` (and abort if < 0.01).

## 7) Inventory + Quote Skew (to nudge toward hedging)

Track per-market filled inventory (rehydrated from positions + updated from order-status deltas):
- `inv_up_shares`, `inv_dn_shares`
- `imbalance = inv_up_shares - inv_dn_shares` (positive means “more UP than DOWN”)

Compute skew ticks (linear, capped):
- `abs = |imbalance|`
- `scale = clamp(abs / complete-set-imbalance-shares-for-max-skew, 0..1)`
- `skew = round(scale * complete-set-max-skew-ticks)`

Apply to legs:
- If `imbalance > 0` (too much UP): favor DOWN (raise DOWN bid, lower UP bid)
  - `skewTicksDown = +skew`, `skewTicksUp = -skew`
- If `imbalance < 0` (too much DOWN): favor UP
  - `skewTicksUp = +skew`, `skewTicksDown = -skew`

Defaults (tuned to avoid “perfect hedging”):
- `complete-set-max-skew-ticks: 1`
- `complete-set-imbalance-shares-for-max-skew: 200`

## 8) Order Management (Maker Quotes)

Maintain at most **one working order per token**:
- If no existing order: place.
- If existing order and (`price` or `size`) changed:
  - Only cancel/replace if the existing order age >= `min-replace-millis` (default 5000ms).

Orders are GTC BUY limit orders.

## 9) Sizing (Replica Share Sizes + Bankroll Caps)

Baseline share sizing is discrete and depends on series + time-to-end bucket:

**BTC 15m (`btc-updown-15m-*`)**
- `< 60s`: 11
- `< 180s`: 13
- `< 300s`: 17
- `< 600s`: 19
- `>= 600s`: 20

**ETH 15m (`eth-updown-15m-*`)**
- `< 60s`: 8
- `< 180s`: 10
- `< 300s`: 12
- `< 600s`: 13
- `>= 600s`: 14

**BTC 1h (`bitcoin-up-or-down-*`)**
- `< 60s`: 9
- `< 180s`: 10
- `< 300s`: 11
- `< 600s`: 12
- `< 900s`: 14
- `< 1200s`: 15
- `< 1800s`: 17
- `>= 1800s`: 18

**ETH 1h (`ethereum-up-or-down-*`)**
- `< 60s`: 7
- `< 300s`: 8
- `< 600s`: 9
- `< 900s`: 11
- `< 1200s`: 12
- `< 1800s`: 13
- `>= 1800s`: 14

Then apply caps (if enabled):
- Set `bankroll-usd > 0` to enable bankroll-based caps.
- Per-order cap: `max-order-bankroll-fraction * bankroll-usd`
- Total cap: `max-total-bankroll-fraction * bankroll-usd`
- Total exposure includes:
  - open order remaining notional
  - open positions notional (from positions snapshot)
  - fills since last positions refresh (to avoid undercounting during the 5s cache window)

If the resulting shares < 0.01: don’t place.

## 10) Taker Top-Ups (Hedge Completion)

Two taker-style mechanisms buy the **lagging** leg at the **best ask** (only if spread <= `taker-mode-max-spread`):

### A) End-of-market top-up
If enabled (`complete-set-top-up-enabled: true`) and:
- `seconds_to_end <= complete-set-top-up-seconds-to-end` (default 60s)
- `abs(imbalance) >= complete-set-top-up-min-shares` (default 10)
Then buy the lagging leg at ask for `topUpShares = abs(imbalance)` (subject to caps).

### B) Fast top-up after a recent lead fill
If enabled (`complete-set-fast-top-up-enabled: true`) and:
- `abs(imbalance) >= complete-set-fast-top-up-min-shares` (default 10)
- cooldown passed since last top-up (`complete-set-fast-top-up-cooldown-millis`, default 15000ms)
- time since the *lead* leg last fill is within:
  - `complete-set-fast-top-up-min-seconds-after-fill` (default 3)
  - `complete-set-fast-top-up-max-seconds-after-fill` (default 120)
- and the “hedged edge” is still acceptable:
  - `hedgedEdge = 1 - (leadFillPrice + laggingBestAsk) >= complete-set-fast-top-up-min-edge` (default 0.01)
Then buy the lagging leg at ask for `topUpShares = abs(imbalance)` (subject to caps).

## 11) Optional: Taker Entry Mode (Disabled by Default)

There is a taker-entry mode in code, but `taker-mode-enabled: false` by default.

If enabled, it triggers when:
- `complete_set_edge < taker-mode-max-edge` (default 1.5%)
- AND both legs have spread <= `taker-mode-max-spread` (default 0.02)
Then the bot enters “taker mode” and crosses the spread (buys at ask) instead of maker quoting.

## 12) Recommended Live Safety for $200 (Configuration Guidance)

To go live with a small bankroll, keep share sizing but add strict caps. Example:

```yaml
hft:
  mode: LIVE
  executor:
    send-live-ack: true   # only when you intentionally go live

hft:
  strategy:
    gabagool:
      bankroll-usd: 200
      max-order-bankroll-fraction: 0.05   # <= $10 per order
      max-total-bankroll-fraction: 0.50   # <= $100 total exposure
      # Optional: reduce trade intensity
      refresh-millis: 750
      min-replace-millis: 10000
```

If you need even safer behavior, reduce `max-total-bankroll-fraction` first.

