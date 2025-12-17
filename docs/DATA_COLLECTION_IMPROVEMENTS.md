# Implementation Guide: Enhanced Data Collection

## 1. Full Order Book Capture (HIGH PRIORITY)

### Current State
You capture only `bestBid`, `bestAsk`, `mid`, `spread`.

### What to Add
Capture top 10 levels of order book depth to understand:
- Whether gabagool22's trade moves the market
- How much liquidity is available at different price points
- If there are walls or support/resistance levels

### Implementation Steps

**Step 1: Update ClickHouse schema**
```sql
-- Add to polybot.clob_tob table
ALTER TABLE polybot.clob_tob ADD COLUMN bids Array(Tuple(price Float64, size Float64)) AFTER best_ask_size;
ALTER TABLE polybot.clob_tob ADD COLUMN asks Array(Tuple(price Float64, size Float64)) AFTER bids;
ALTER TABLE polybot.clob_tob ADD COLUMN total_bids_volume Float64 AFTER asks;
ALTER TABLE polybot.clob_tob ADD COLUMN total_asks_volume Float64 AFTER total_bids_volume;
```

**Step 2: Update ingestor CLOB API client**
In `PolymarketClobApiClient.java`, parse full book from response:
```java
// Currently you only extract top-of-book
// Add method to extract levels:
public List<OrderLevel> extractBidLevels(JsonNode book, int limit) {
  List<OrderLevel> levels = new ArrayList<>();
  JsonNode bids = book.path("bids");
  for (int i = 0; i < Math.min(limit, bids.size()); i++) {
    JsonNode level = bids.get(i);
    levels.add(new OrderLevel(
      level.path(0).asDouble(),  // price
      level.path(1).asDouble()   // size
    ));
  }
  return levels;
}
```

**Step 3: Update publish logic**
In `maybePublishClobTob()`:
```java
// Add to tob map
List<OrderLevel> bidLevels = clobApi.extractBidLevels(book, 10);
List<OrderLevel> askLevels = clobApi.extractAskLevels(book, 10);
tob.put("bids", bidLevels);
tob.put("asks", askLevels);
tob.put("totalBidsVolume", bidLevels.stream().mapToDouble(l -> l.size()).sum());
tob.put("totalAsksVolume", askLevels.stream().mapToDouble(l -> l.size()).sum());
```

**Effort:** ~3 hours
**Impact:** Reveals if gabagool22 has market impact or exploits depth

---

## 2. Resolution Events Tracking (HIGH PRIORITY)

### Current State
Market resolution happens but you only see outcome prices in gamma snapshots.

### What's Missing
- Exact resolution timestamp
- Settlement time (when funds actually transfer)
- Resolution source (UMA oracle, market creator, etc.)
- Dispute period details

### Implementation Steps

**Step 1: Create new table**
```sql
CREATE TABLE IF NOT EXISTS polybot.market_resolutions (
  market_slug LowCardinality(String),
  market_id String,
  resolved_outcome LowCardinality(String),
  outcome_prices Array(Float64),
  resolved_at DateTime64(3),
  settlement_at DateTime64(3),
  uma_resolution_status LowCardinality(String),
  resolution_source String,  -- 'oracle', 'market_creator', 'uma_dispute', etc.
  event_key String,
  captured_at DateTime64(3),
  ingested_at DateTime64(3)
)
ENGINE = MergeTree
PARTITION BY toDate(resolved_at)
ORDER BY (market_slug, resolved_at);
```

**Step 2: Create materialized view**
```sql
CREATE MATERIALIZED VIEW polybot.market_resolutions_mv
TO polybot.market_resolutions
AS
SELECT
  slug AS market_slug,
  market_id,
  -- Determine resolved outcome
  if(arrayMax(outcome_prices) >= 0.999, 'Yes/Up', if(arrayMin(outcome_prices) <= 0.001, 'No/Down', NULL)) AS resolved_outcome,
  outcome_prices,
  captured_at AS resolved_at,
  -- Estimate settlement (typically 24-48h after resolution)
  captured_at + INTERVAL 24 HOUR AS settlement_at,
  uma_resolution_status,
  'gamma_api' AS resolution_source,
  kafka_key AS event_key,
  captured_at,
  ingested_at
FROM polybot.gamma_markets
WHERE (arrayMax(outcome_prices) >= 0.999 OR arrayMin(outcome_prices) <= 0.001);
```

**Step 3: Join with trades**
```sql
-- In your analysis queries, join to get settlement PnL timing:
SELECT
  u.username,
  u.market_slug,
  u.ts AS trade_time,
  r.resolved_at,
  dateDiff('hour', u.ts, r.resolved_at) AS hours_to_resolution,
  u.settle_price,
  u.realized_pnl
FROM polybot.user_trade_enriched u
LEFT JOIN polybot.market_resolutions r ON r.market_slug = u.market_slug;
```

**Effort:** ~2 hours
**Impact:** Accurate PnL timing and settlement analysis

---

## 3. Trade Execution Enrichment (MEDIUM PRIORITY)

### Current State
You capture trade price, size, timestamp, but miss execution details.

### What to Add
- Gas price paid (indicator of urgency)
- Transaction status (confirm/revert)
- Block timestamp vs trade timestamp (execution delay)
- MEV/sandwich indicators

### Implementation Steps

**Step 1: Update Polymarket API client**
```java
// The data API may already provide this, check the response:
// trade.gasPrice
// trade.gasUsed
// trade.blockNumber
// trade.blockTimestamp

// If not available, query Etherscan:
public TradeExecutionDetails getExecutionDetails(String txHash) {
  // Call Etherscan API or your Ethereum node
  Response response = etherscanClient.getTransactionByHash(txHash);
  return new TradeExecutionDetails(
    response.gasPrice,
    response.gasUsed,
    response.blockTimestamp,
    response.status  // 1 = success, 0 = reverted
  );
}
```

**Step 2: Enhance trade event**
```java
// In publishTrades():
Map<String, Object> executionData = new LinkedHashMap<>();
executionData.put("trade", trade);

// Add execution details if available
TradeExecutionDetails exec = getExecutionDetails(trade.path("transactionHash").asText());
if (exec != null) {
  executionData.put("gasPrice", exec.gasPrice);
  executionData.put("gasUsed", exec.gasUsed);
  executionData.put("blockTimestamp", exec.blockTimestamp);
  executionData.put("executionSucceeded", exec.status == 1);
}

events.publish(ts, "polymarket.user.trade.enriched", eventKey, executionData);
```

**Step 3: Update ClickHouse**
```sql
ALTER TABLE polybot.user_trades ADD COLUMN gas_price Float64 AFTER transaction_hash;
ALTER TABLE polybot.user_trades ADD COLUMN gas_used Float64 AFTER gas_price;
ALTER TABLE polybot.user_trades ADD COLUMN block_timestamp DateTime64(3) AFTER gas_used;
ALTER TABLE polybot.user_trades ADD COLUMN execution_succeeded UInt8 AFTER block_timestamp;
```

**Effort:** ~2-3 hours (depends on API availability)
**Impact:** Detect urgency patterns and execution failures

---

## 4. Liquidity & Flow Metrics (MEDIUM PRIORITY)

### What to Track
For each trade, capture market state metrics:

```sql
-- Add to user_trade_enriched:
- market_volume_1m_before: total market volume in last 1 min
- market_volume_1m_after: market volume in next 1 min
- bid_ask_imbalance: (bids - asks) / (bids + asks)
- orderbook_depth_1pct: total volume within 1% of mid price
- realized_volatility_5m: standard deviation of prices in last 5 min
- volume_velocity: acceleration of volume (d²V/dt²)
```

### Implementation

Already have structure in place! Just need to fully implement:

**microstructure.sql already has:**
- `market_volume_1m_before`
- `market_volume_1m_after`
- `price_range_1m_before`
- `vwap_1m_before`

**Just add:**
```sql
ALTER TABLE polybot.market_trade_activity_1m ADD COLUMN bid_ask_imbalance Float64;
ALTER TABLE polybot.market_trade_activity_1m ADD COLUMN depth_1pct Float64;
ALTER TABLE polybot.market_trade_activity_1m ADD COLUMN volume_acceleration Float64;
```

**Effort:** ~1 hour
**Impact:** Understand timing selection (does he trade during momentum vs low liquidity?)

---

## 5. Data Quality Dashboard (LOW PRIORITY - but useful)

Monitor ingestor health with these queries:

```sql
-- TOB coverage per market
SELECT
  market_slug,
  count() as total_trades,
  countIf(tob_captured_at IS NOT NULL) as trades_with_tob,
  round(countIf(tob_captured_at IS NOT NULL) / count() * 100, 1) as coverage_pct
FROM polybot.user_trade_enriched
WHERE username = 'gabagool22'
GROUP BY market_slug
ORDER BY coverage_pct DESC;

-- Data freshness
SELECT
  type,
  max(ts) as latest_event,
  dateDiff('minute', max(ts), now()) as minutes_ago
FROM polybot.analytics_events
GROUP BY type;

-- Duplicate rate
SELECT
  count() as total,
  uniqExact(event_key) as unique,
  round((1 - uniqExact(event_key) / count()) * 100, 2) as duplicate_pct
FROM polybot.user_trades
WHERE username = 'gabagool22';
```

---

## Implementation Priority Timeline

```
Week 1:
  ✓ Resolution events tracking (2 hrs)
  ✓ Trade execution enrichment (2 hrs)

Week 2:
  ✓ Full order book capture (3 hrs)
  ✓ Liquidity metrics (1 hr)

Week 3:
  ✓ Testing and validation
  ✓ Backfill historical data with new fields
```

---

## Expected Impact on Strategy Discovery

With these additions:

| Question | Current Capability | After Improvements |
|----------|-------------------|-------------------|
| "Does he move the market?" | ❓ Unknown | ✅ Full order book |
| "Is he urgent?" | ❓ Unknown | ✅ Gas price analysis |
| "Does he pick liquid markets?" | ⚠️ Partial | ✅ Full flow metrics |
| "When does he exit?" | ❓ Unknown | ✅ Settlement timing |
| "What's the exact PnL?" | ✅ Good | ✅ Better (resolution events) |
| "Is he predicting or reacting?" | ⚠️ Partial | ✅ Full microstructure |


