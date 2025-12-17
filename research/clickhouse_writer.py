"""
Utilities for writing research labels back to ClickHouse.

This module provides functions to persist Python-generated labels (clusters, regimes, etc.)
back to ClickHouse so they can be served via the analytics API.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from typing import Literal

import pandas as pd

LabelType = Literal["cluster", "regime", "complete_set", "signal", "archetype"]


def get_clickhouse_client(
    *,
    host: str | None = None,
    port: int | None = None,
    username: str | None = None,
    password: str | None = None,
    database: str = "polybot",
):
    """
    Get a ClickHouse client using clickhouse-connect.

    Falls back to environment variables if parameters not provided:
    - CLICKHOUSE_HOST (default: localhost)
    - CLICKHOUSE_PORT (default: 8123)
    - CLICKHOUSE_USER (default: default)
    - CLICKHOUSE_PASSWORD (default: empty)
    """
    try:
        import clickhouse_connect
    except ImportError:
        raise ImportError(
            "clickhouse-connect is required for write-back. "
            "Install with: pip install clickhouse-connect"
        )

    host = host or os.getenv("CLICKHOUSE_HOST", "localhost")
    port = port or int(os.getenv("CLICKHOUSE_PORT", "8123"))
    username = username or os.getenv("CLICKHOUSE_USER", "default")
    password = password or os.getenv("CLICKHOUSE_PASSWORD", "")

    return clickhouse_connect.get_client(
        host=host,
        port=port,
        username=username,
        password=password,
        database=database,
    )


def write_labels(
    df: pd.DataFrame,
    *,
    label_type: LabelType,
    label_col: str,
    score_col: str | None = None,
    model_version: str = "v1",
    username_col: str = "username",
    event_key_col: str = "event_key",
    host: str | None = None,
    port: int | None = None,
    batch_size: int = 10_000,
) -> int:
    """
    Write labels from a DataFrame to ClickHouse research_labels table.

    Args:
        df: DataFrame with trade data including event_key and label columns
        label_type: Type of label (cluster, regime, complete_set, signal, archetype)
        label_col: Column name containing the label values
        score_col: Optional column name containing numeric scores
        model_version: Version string for tracking model iterations
        username_col: Column name for username (default: 'username')
        event_key_col: Column name for event key (default: 'event_key')
        host: ClickHouse host (default: from env or localhost)
        port: ClickHouse port (default: from env or 8123)
        batch_size: Number of rows to insert per batch

    Returns:
        Number of rows inserted
    """
    if df.empty:
        return 0

    required_cols = [username_col, event_key_col, label_col]
    missing = [c for c in required_cols if c not in df.columns]
    if missing:
        raise ValueError(f"Missing required columns: {missing}")

    client = get_clickhouse_client(host=host, port=port)

    now = datetime.now(timezone.utc)
    rows_inserted = 0

    # Process in batches
    for start in range(0, len(df), batch_size):
        batch = df.iloc[start:start + batch_size]

        records = []
        for _, row in batch.iterrows():
            record = {
                "event_key": str(row[event_key_col]),
                "username": str(row[username_col]),
                "label_type": label_type,
                "label_value": str(row[label_col]),
                "label_score": float(row[score_col]) if score_col and pd.notna(row.get(score_col)) else 0.0,
                "labeled_at": now,
                "model_version": model_version,
            }
            records.append(record)

        # Insert batch
        client.insert(
            "research_labels",
            [list(r.values()) for r in records],
            column_names=list(records[0].keys()),
        )
        rows_inserted += len(records)

    return rows_inserted


def write_cluster_labels(
    df: pd.DataFrame,
    *,
    cluster_col: str = "cluster",
    model_version: str = "v1",
    **kwargs,
) -> int:
    """
    Convenience function to write cluster labels.
    """
    return write_labels(
        df,
        label_type="cluster",
        label_col=cluster_col,
        model_version=model_version,
        **kwargs,
    )


def write_complete_set_labels(
    df: pd.DataFrame,
    *,
    edge_col: str = "edge",
    model_version: str = "v1",
    **kwargs,
) -> int:
    """
    Write complete-set detection results.

    Expects DataFrame with columns: event_key, username, and edge (or specified edge_col).
    The edge value is stored as label_score.
    """
    # Create a flag column
    df = df.copy()
    df["_cs_flag"] = "true"

    return write_labels(
        df,
        label_type="complete_set",
        label_col="_cs_flag",
        score_col=edge_col,
        model_version=model_version,
        **kwargs,
    )


def delete_labels(
    *,
    username: str,
    label_type: LabelType | None = None,
    model_version: str | None = None,
    host: str | None = None,
    port: int | None = None,
) -> int:
    """
    Delete existing labels for a user (useful before re-running analysis).

    Returns approximate number of rows affected.
    """
    client = get_clickhouse_client(host=host, port=port)

    conditions = [f"username = '{username}'"]
    if label_type:
        conditions.append(f"label_type = '{label_type}'")
    if model_version:
        conditions.append(f"model_version = '{model_version}'")

    where_clause = " AND ".join(conditions)

    # Get count first
    count_result = client.query(f"SELECT count() FROM research_labels WHERE {where_clause}")
    count = count_result.result_rows[0][0] if count_result.result_rows else 0

    # Delete
    client.command(f"ALTER TABLE research_labels DELETE WHERE {where_clause}")

    return count


def get_label_summary(
    *,
    username: str | None = None,
    host: str | None = None,
    port: int | None = None,
) -> pd.DataFrame:
    """
    Get summary of labels in the database.
    """
    client = get_clickhouse_client(host=host, port=port)

    query = """
    SELECT
        username,
        label_type,
        count() AS label_count,
        uniqExact(label_value) AS unique_values,
        min(labeled_at) AS first_labeled_at,
        max(labeled_at) AS last_labeled_at,
        argMax(model_version, labeled_at) AS latest_model_version
    FROM research_labels
    """

    if username:
        query += f" WHERE username = '{username}'"

    query += " GROUP BY username, label_type ORDER BY username, label_type"

    result = client.query(query)

    return pd.DataFrame(
        result.result_rows,
        columns=["username", "label_type", "label_count", "unique_values",
                 "first_labeled_at", "last_labeled_at", "latest_model_version"]
    )

