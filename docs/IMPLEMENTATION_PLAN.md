# gabagool22 Strategy Reverse-Engineering — Implementation Plan

**Goal:** Complete the quant research pipeline to fully reverse-engineer gabagool22's trading strategy.

**Current State:** ✅ **COMPLETE** — All 7 phases implemented!

---

## Implementation Status

| Step | Task | Status | Files Created/Modified |
|------|------|--------|------------------------|
| 1 | Complete-set detector in Python | ✅ Done | `04_backtest_and_montecarlo.ipynb` |
| 2 | ClickHouse position ledger views | ✅ Done | `005_position_ledger.sql` |
| 3 | Microstructure features view | ✅ Done | `006_microstructure.sql` |
| 4 | Timing stability notebook section | ✅ Done | `03_model_and_tests.ipynb` |
| 5 | Clustering notebook section | ✅ Done | `03_model_and_tests.ipynb` |
| 6 | Write-back infrastructure | ✅ Done | `007_research_labels.sql`, `clickhouse_writer.py` |
| 7 | New analytics endpoints | ✅ Done | `UserPositionAnalyticsRepository.java`, `JdbcUserPositionAnalyticsRepository.java`, `UserPositionAnalyticsController.java` |

---

## Phase 1: Position Ledger & Complete-Set Detection (ClickHouse)

### 1.1 Create `user_position_ledger` view
**File:** `analytics-service/clickhouse/init/005_position_ledger.sql`

A running ledger per `(username, market_slug, token_id)` that computes:
- `signed_shares` (BUY = +, SELL = -)
- `signed_cost_usd` (BUY = +price×size, SELL = -price×size)
- Running `position_shares` and `position_cost_usd`
- `avg_entry_price` = position_cost / position_shares (when position > 0)

```sql
-- Pseudo-structure:
CREATE VIEW polybot.user_position_ledger AS
SELECT
  ts,
  username,
  market_slug,
  token_id,
  outcome,
  side,
  price,
  size,
  if(side = 'BUY', size, -size) AS signed_shares,
  if(side = 'BUY', price * size, -price * size) AS signed_cost_usd,
  sum(signed_shares) OVER w AS position_shares,
  sum(signed_cost_usd) OVER w AS position_cost_usd,
  if(position_shares > 0, position_cost_usd / position_shares, NULL) AS avg_entry_price
FROM user_trades_dedup
WINDOW w AS (PARTITION BY username, market_slug, token_id ORDER BY ts ROWS UNBOUNDED PRECEDING)
ORDER BY username, market_slug, token_id, ts;
```

### 1.2 Create `user_complete_sets_detected` view
**File:** `analytics-service/clickhouse/init/005_position_ledger.sql`

Time-window based complete-set detection (pairs of YES/NO buys within N seconds):

```sql
-- Detect complete-set pairs within 60s window
CREATE VIEW polybot.user_complete_sets_detected AS
WITH window_seconds AS 60
SELECT
  u1.username,
  u1.market_slug,
  u1.ts AS ts_1,
  u1.outcome AS outcome_1,
  u1.price AS price_1,
  u1.size AS size_1,
  u2.ts AS ts_2,
  u2.outcome AS outcome_2,
  u2.price AS price_2,
  u2.size AS size_2,
  least(u1.size, u2.size) AS matched_size,
  u1.price + u2.price AS combined_cost,
  1 - (u1.price + u2.price) AS edge_per_share,
  least(u1.size, u2.size) * (1 - (u1.price + u2.price)) AS edge_pnl
FROM user_trades_dedup u1
INNER JOIN user_trades_dedup u2
  ON u1.username = u2.username
  AND u1.market_slug = u2.market_slug
  AND u1.outcome != u2.outcome
  AND u1.side = 'BUY' AND u2.side = 'BUY'
  AND abs(dateDiff('second', u1.ts, u2.ts)) <= window_seconds
  AND u1.ts <= u2.ts  -- avoid duplicates
WHERE u1.outcome IN ('Up', 'Down', 'Yes', 'No')
  AND u2.outcome IN ('Up', 'Down', 'Yes', 'No');
```

### 1.3 Expose new endpoints in analytics-service
**Files:**
- `UserPositionAnalyticsRepository.java` — add `positionLedger(username, marketSlug, tokenId)`
- `JdbcUserPositionAnalyticsRepository.java` — implement query
- `UserPositionAnalyticsController.java` — expose endpoint

---

## Phase 2: Market Microstructure Features (ClickHouse)

### 2.1 Create `user_trade_with_microstructure` view
**File:** `analytics-service/clickhouse/init/006_microstructure.sql`

Join user trades with nearby market trades to compute:
- `market_volume_1m_before` — total volume in this token 1 min before trade
- `market_trade_count_1m_before` — count of trades 1 min before
- `market_volume_1m_after` — volume 1 min after (for impact analysis)
- `vwap_1m_before` — volume-weighted average price before
- `last_market_trade_price` — most recent market trade price before user trade
- `time_since_last_trade_ms` — milliseconds since last market trade

```sql
CREATE VIEW polybot.user_trade_with_microstructure AS
SELECT
  u.*,
  -- Volume before trade
  (
    SELECT sum(size)
    FROM market_trades m
    WHERE m.token_id = u.token_id
      AND m.ts >= u.ts - INTERVAL 60 SECOND
      AND m.ts < u.ts
  ) AS market_volume_1m_before,
  
  -- Trade count before
  (
    SELECT count()
    FROM market_trades m
    WHERE m.token_id = u.token_id
      AND m.ts >= u.ts - INTERVAL 60 SECOND
      AND m.ts < u.ts
  ) AS market_trade_count_1m_before,
  
  -- VWAP before
  (
    SELECT sum(price * size) / nullIf(sum(size), 0)
    FROM market_trades m
    WHERE m.token_id = u.token_id
      AND m.ts >= u.ts - INTERVAL 60 SECOND
      AND m.ts < u.ts
  ) AS vwap_1m_before

FROM user_trade_research u;
```

**Note:** This may be expensive. Consider materializing as a table with incremental refresh.

---

## Phase 3: Complete-Set Detector in Python Notebook

### 3.1 Add to `04_backtest_and_montecarlo.ipynb`
**Already outlined in previous conversation.** Add cells for:
- `detect_complete_sets()` function
- Summary stats (total edge PnL, avg edge per set, positive edge %)
- Visualization of complete-set edge distribution
- Save results to `complete_sets.csv`

---

## Phase 4: Timing Stability Analysis (Notebook)

### 4.1 Add to `03_model_and_tests.ipynb`

New section: **Timing Stability Over Time**

```python
# Daily timing evolution
timing_by_day = df.groupby([df["ts"].dt.date, "asset"]).agg(
    trades=("ts", "size"),
    p50_seconds_to_end=("seconds_to_end", lambda x: x.quantile(0.5)),
    avg_seconds_to_end=("seconds_to_end", "mean"),
).reset_index()

# Plot heatmap: day vs asset, colored by p50_seconds_to_end
# Check for regime changes in timing behavior
```

---

## Phase 5: Clustering & Pattern Detection (Notebook)

### 5.1 Enhance `03_model_and_tests.ipynb`

**Trade Archetype Discovery:**

Features for clustering:
- `seconds_to_end` (normalized)
- `price` (normalized)
- `size` (normalized)
- `spread` at entry
- `tob_imbalance` at entry
- `hour_utc`
- `outcome` (one-hot)
- `is_complete_set` (flag)

```python
from sklearn.preprocessing import StandardScaler
from sklearn.cluster import KMeans

cluster_features = [
    "seconds_to_end", "price", "size", "spread",
    "tob_imbalance", "hour_utc"
]
X = df[cluster_features].dropna()
X_scaled = StandardScaler().fit_transform(X)

kmeans = KMeans(n_clusters=k_clusters, random_state=42)
df.loc[X.index, "cluster"] = kmeans.fit_predict(X_scaled)

# Analyze each cluster
for c in range(k_clusters):
    cluster_df = df[df["cluster"] == c]
    print(f"Cluster {c}: n={len(cluster_df)}, avg_pnl={cluster_df['realized_pnl'].mean():.2f}")
```

---

## Phase 6: Write-Back to ClickHouse

### 6.1 Create research labels table
**File:** `analytics-service/clickhouse/init/007_research_labels.sql`

```sql
CREATE TABLE IF NOT EXISTS polybot.research_labels (
  event_key String,
  username LowCardinality(String),
  label_type LowCardinality(String),  -- 'cluster', 'regime', 'complete_set', etc.
  label_value String,
  label_score Float64,
  labeled_at DateTime64(3),
  model_version LowCardinality(String)
)
ENGINE = ReplacingMergeTree(labeled_at)
ORDER BY (username, event_key, label_type);
```

### 6.2 Python write-back utility
**File:** `research/clickhouse_writer.py`

```python
import clickhouse_connect

def write_labels(
    df: pd.DataFrame,
    *,
    label_type: str,
    label_col: str,
    score_col: str | None = None,
    model_version: str = "v1",
    host: str = "localhost",
    port: int = 8123,
):
    client = clickhouse_connect.get_client(host=host, port=port)
    
    records = []
    for _, row in df.iterrows():
        records.append({
            "event_key": row["event_key"],
            "username": row["username"],
            "label_type": label_type,
            "label_value": str(row[label_col]),
            "label_score": float(row[score_col]) if score_col else 0.0,
            "labeled_at": pd.Timestamp.now(tz="UTC"),
            "model_version": model_version,
        })
    
    client.insert("polybot.research_labels", records)
```

---

## Phase 7: Enhanced Analytics Endpoints

### 7.1 New endpoints to add

| Endpoint | Purpose |
|----------|---------|
| `GET /users/{username}/complete-sets/detected` | Time-window detected complete sets |
| `GET /users/{username}/positions/ledger/{marketSlug}` | Full position ledger for a market |
| `GET /users/{username}/microstructure/summary` | Avg volume/liquidity at trade time |
| `GET /users/{username}/timing/stability` | Daily timing evolution |
| `GET /users/{username}/clusters` | Trade cluster assignments |

---

## Implementation Order (Recommended)

| Step | Task | Effort | Priority |
|------|------|--------|----------|
| 1 | Phase 3: Complete-set detector in Python | 30 min | HIGH |
| 2 | Phase 1.1-1.2: ClickHouse position ledger views | 1 hr | HIGH |
| 3 | Phase 2: Microstructure features view | 1 hr | MEDIUM |
| 4 | Phase 4: Timing stability notebook section | 30 min | MEDIUM |
| 5 | Phase 5: Clustering notebook section | 1 hr | MEDIUM |
| 6 | Phase 6: Write-back infrastructure | 1 hr | LOW |
| 7 | Phase 1.3 + 7: New analytics endpoints | 2 hr | LOW |

---

## Success Metrics

After completing this plan, you should be able to answer:

1. **What % of gabagool22's PnL comes from complete-set arbitrage?**
2. **What is his timing model?** (seconds_to_end distribution, stability over time)
3. **What drives his execution alpha?** (maker/taker mix, spread capture)
4. **Are there distinct trade archetypes?** (clusters with different risk/return profiles)
5. **Can we replicate his signal?** (directional model walk-forward performance)

---

## Next Steps

Ready to start? I'll implement **Step 1 (Phase 3)** first — the complete-set detector in Python, since it's highest priority and quickest to validate.

