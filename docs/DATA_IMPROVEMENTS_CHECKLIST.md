# Data Collection Improvement Checklist

## Current Baseline
- ✅ 20,678 user trades captured
- ✅ 100% TOB coverage
- ✅ 21,500 market trades
- ⚠️ 355 gamma snapshots (sparse)
- ⚠️ 989 position snapshots (sparse)

**Overall: 80% Complete → Target: 95% Complete**

---

## Priority 1: Full Order Book Depth (3 hours)

### What to Implement
Capture top 10 levels of bid/ask book at trade time (not just best bid/ask).

### Why
- **See if he exploits book imbalances** — does he buy where there's sudden depth drop?
- **Understand market impact** — does his trade move prices after?
- **Detect liquidity-seeking behavior** — does he care about slippage?

### Files to Modify
- [ ] `PolymarketClobApiClient.java` — add method to extract levels from response
- [ ] `PolymarketMarketContextIngestor.java` — call new method, add to TOB event
- [ ] `002_canonical.sql` — add columns: `bids`, `asks`, `total_bids_volume`, `total_asks_volume`
- [ ] Tests — verify extraction logic

### Pseudo-code
```java
// In PolymarketClobApiClient
private List<OrderLevel> extractLevels(JsonNode book, String side, int limit) {
  JsonNode levels = book.path(side);  // "bids" or "asks"
  List<OrderLevel> result = new ArrayList<>();
  for (int i = 0; i < Math.min(limit, levels.size()); i++) {
    result.add(new OrderLevel(
      levels.get(i).get(0).asDouble(),  // price
      levels.get(i).get(1).asDouble()   // size
    ));
  }
  return result;
}
```

### Data Impact
After: Will know if gabagool22 fills at mid-price vs exploiting depth

**Estimated effort: 2-3 hours**

---

## Priority 2: Resolution Event Tracking (2 hours)

### What to Implement
Track when markets resolve (outcome determined) and when settlement happens (funds transfer).

### Why
- **Accurate PnL timing** — not all trades realize PnL at market close
- **Resolution lag exploitation** — some traders profit from delays
- **Settlement patterns** — understand when he can actually use the cash

### Files to Modify
- [ ] `PolymarketGammaApiClient.java` — flag when market transitions to resolved state
- [ ] New SQL file: `008_market_events.sql` — create `market_resolutions` table
- [ ] `PolymarketMarketContextIngestor.java` — publish resolution event
- [ ] `002_canonical.sql` — join gamma→resolution timestamp

### Pseudo-code
```java
// Detect resolution transition
boolean wasResolved = previousGamma.resolved;
boolean isResolved = currentGamma.resolved || 
                     currentGamma.umaResolutionStatus != null;

if (!wasResolved && isResolved) {
  publishResolutionEvent(gamma);
}
```

### Data Impact
After: Will know exact resolution timing for all ~20k trades

**Estimated effort: 1.5-2 hours**

---

## Priority 3: Trade Execution Details (2 hours)

### What to Implement
Capture gas price, block timestamp, and transaction status for each trade.

### Why
- **Detect urgency** — high gas = time-sensitive trade
- **Find retry patterns** — failed trades may indicate strategy changes
- **MEV awareness** — understand if he's sandwich-attacked

### Files to Modify
- [ ] `PolymarketDataApiClient.java` — check if API response includes `gasPrice`, `blockTimestamp`
- [ ] If not in API, add Etherscan integration (optional):
  - [ ] `EtherscanClient.java` — query transaction details by hash
  - [ ] `PolymarketUserIngestor.java` — call Etherscan for each trade
- [ ] `002_canonical.sql` — add columns: `gas_price`, `block_timestamp`, `tx_status`
- [ ] Rate limiting — Etherscan has limits, add backoff

### Pseudo-code
```java
// In PolymarketUserIngestor.publishTrades()
String txHash = trade.path("transactionHash").asText();

// Check if API already provides:
if (trade.has("gasPrice")) {
  data.put("gasPrice", trade.get("gasPrice"));
} else {
  // Fallback to Etherscan
  TxDetails details = etherscanClient.getTransaction(txHash);
  data.put("gasPrice", details.gasPrice);
  data.put("blockTimestamp", details.blockTime);
}

events.publish(ts, "polymarket.user.trade", eventKey, data);
```

### Data Impact
After: Will know if trades were rushed, failed, or delayed

**Estimated effort: 1.5-2 hours (+ Etherscan API setup if needed)**

---

## Priority 4: Market Flow Metrics (1 hour)

### What to Implement
Add computed metrics to trades: volume velocity, imbalance, volatility context.

### Why
- **Understand timing selection** — does he trade during momentum or calm?
- **Liquidity awareness** — does he wait for good liquidity?
- **Volatility sensitivity** — does he avoid/seek volatility?

### Files to Modify
- [ ] `006_microstructure.sql` — update `user_trade_with_microstructure` view
- [ ] Add columns:
  - `market_volume_1m_before` ← already exists ✅
  - `market_volume_acceleration` ← NEW: dV/dt
  - `bid_ask_imbalance` ← NEW: (bids-asks)/(bids+asks)
  - `realized_volatility_5m` ← NEW: stdev(prices in last 5min)

### Pseudo-code
```sql
-- Add to market trades aggregation
market_volume_acceleration = 
  (volume_now - volume_5m_ago) / 5min,

bid_ask_imbalance = 
  (sum(asks) - sum(bids)) / (sum(asks) + sum(bids)),

realized_volatility_5m = 
  stddev(log(price) - lag(log(price)))
```

### Data Impact
After: Will know if he's a momentum trader or contrarian

**Estimated effort: 0.5-1 hour**

---

## Optional: Competitor Tracking (6 hours)

### What to Implement
Track other traders' activity to find correlations.

### Why
- **Detect front-running** — does he trade before/after others?
- **Crowding analysis** — is strategy becoming mainstream?
- **Correlation patterns** — does he follow or lead market?

### Complexity
- Need to track ALL user addresses (expensive)
- Requires separate data pipeline
- May hit rate limits

### Recommendation
**Skip for now** — focus on single-user depth first

---

## Implementation Roadmap

```
Week 1 (This Week):
  [ ] Priority 1: Full order book (3 hrs) - 30% time investment
  [ ] Priority 2: Resolution tracking (2 hrs) - 20% time investment
  
Week 2:
  [ ] Priority 3: Execution details (2 hrs) - 20% time investment
  [ ] Priority 4: Flow metrics (1 hr) - 10% time investment
  [ ] Testing & validation (2 hrs) - 20% time investment

End of Week 2:
  ✅ 95% data completeness
  ✅ Ready for advanced analysis
  ✅ Can build execution models
```

---

## Testing Checklist After Implementation

### For Full Order Book
- [ ] Verify top 10 levels extracted correctly
- [ ] Check total_bids_volume = sum of all bid levels
- [ ] Confirm book is realistic (prices in right order)
- [ ] Spot-check against live CLOB API

### For Resolution Events
- [ ] Verify `resolved_at` is exactly when market resolves
- [ ] Check `settlement_at` is ~24-48h later
- [ ] Confirm all resolved markets have events
- [ ] Cross-check with gamma API timestamps

### For Execution Details
- [ ] Verify gas prices are in valid range (0.1-1000 gwei)
- [ ] Check block timestamps are increasing monotonically
- [ ] Spot-check failed trades against blockchain
- [ ] Compare Etherscan vs API data (if using both)

### For Flow Metrics
- [ ] Verify `bid_ask_imbalance` is in [-1, 1]
- [ ] Check volatility is >= 0
- [ ] Spot-check computation against raw data
- [ ] Visualize over time (should be smooth)

---

## Success Criteria

You'll know implementation is successful when:

✅ Can answer: "What order book depth had he available when he traded?"
✅ Can answer: "How long until his trade actually realized profit?"
✅ Can answer: "Was he rushing (high gas) or patient?"
✅ Can answer: "Did he trade during momentum or doldrums?"

---

## Questions? 

If you want to implement these, follow priority order:
1. Priority 1 (3 hrs) — highest ROI for understanding market impact
2. Priority 2 (2 hrs) — critical for accurate PnL timing
3. Priority 3 (2 hrs) — nice-to-have for urgency patterns
4. Priority 4 (1 hr) — completes the picture

**Total commitment: ~8-10 hours → 95% data completeness**


