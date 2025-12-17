-- Research labels table for storing Python-generated labels back to ClickHouse.
-- This enables the analytics API to serve research results.

-- =============================================================================
-- RESEARCH LABELS: Store labels/scores from Python analysis
-- =============================================================================

CREATE TABLE IF NOT EXISTS polybot.research_labels (
  event_key String,
  username LowCardinality(String),
  label_type LowCardinality(String),  -- e.g., 'cluster', 'regime', 'complete_set', 'signal'
  label_value String,
  label_score Float64 DEFAULT 0.0,
  labeled_at DateTime64(3) DEFAULT now64(3),
  model_version LowCardinality(String) DEFAULT 'v1'
)
ENGINE = ReplacingMergeTree(labeled_at)
PARTITION BY toDate(labeled_at)
ORDER BY (username, event_key, label_type);

-- Index for efficient lookups
ALTER TABLE polybot.research_labels ADD INDEX IF NOT EXISTS idx_label_type (label_type) TYPE set(100) GRANULARITY 1;


-- =============================================================================
-- VIEW: Join research labels with trades
-- =============================================================================

CREATE OR REPLACE VIEW polybot.user_trade_with_labels AS
SELECT
  u.*,
  -- Cluster label
  cluster.label_value AS cluster_label,
  cluster.label_score AS cluster_score,
  -- Regime label (if different from computed)
  regime_label.label_value AS research_regime,
  -- Complete-set flag
  cs.label_value AS complete_set_flag,
  cs.label_score AS complete_set_edge
FROM polybot.user_trade_research u
LEFT JOIN (
  SELECT event_key, label_value, label_score
  FROM polybot.research_labels
  WHERE label_type = 'cluster'
) cluster ON cluster.event_key = u.event_key
LEFT JOIN (
  SELECT event_key, label_value
  FROM polybot.research_labels
  WHERE label_type = 'regime'
) regime_label ON regime_label.event_key = u.event_key
LEFT JOIN (
  SELECT event_key, label_value, label_score
  FROM polybot.research_labels
  WHERE label_type = 'complete_set'
) cs ON cs.event_key = u.event_key;


-- =============================================================================
-- RESEARCH SUMMARY: Aggregate labels per username
-- =============================================================================

CREATE OR REPLACE VIEW polybot.research_labels_summary AS
SELECT
  username,
  label_type,
  count() AS label_count,
  uniqExact(label_value) AS unique_values,
  min(labeled_at) AS first_labeled_at,
  max(labeled_at) AS last_labeled_at,
  argMax(model_version, labeled_at) AS latest_model_version
FROM polybot.research_labels
GROUP BY username, label_type;

