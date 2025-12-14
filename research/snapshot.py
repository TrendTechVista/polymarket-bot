from __future__ import annotations

import json
import os
import platform
import subprocess
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable


def _require_imports() -> tuple[Any, Any, Any]:
    try:
        import clickhouse_connect  # type: ignore
    except ModuleNotFoundError as e:
        raise RuntimeError(
            "Missing dependency: clickhouse-connect. "
            "Install with: pip install -r research/requirements.txt"
        ) from e

    try:
        import pandas as pd  # type: ignore
    except ModuleNotFoundError as e:
        raise RuntimeError(
            "Missing dependency: pandas. Install with: pip install -r research/requirements.txt"
        ) from e

    try:
        import pyarrow  # noqa: F401  # type: ignore
    except ModuleNotFoundError as e:
        raise RuntimeError(
            "Missing dependency: pyarrow. Install with: pip install -r research/requirements.txt"
        ) from e

    return clickhouse_connect, pd, pyarrow


def _sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _sql_in(values: Iterable[str]) -> str:
    escaped = [_sql_quote(v) for v in values]
    if not escaped:
        return "(NULL)"
    return "(" + ",".join(escaped) + ")"


def _iso_now_utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _repo_root() -> Path:
    # research/snapshot.py -> repo root is one level up from research/
    return Path(__file__).resolve().parents[1]


def _git_sha() -> str | None:
    try:
        return (
            subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=_repo_root())
            .decode("utf-8")
            .strip()
        )
    except Exception:
        return None


@dataclass(frozen=True)
class SnapshotManifest:
    snapshot_id: str
    created_at_utc: str
    username: str
    clickhouse: dict[str, Any]
    git_sha: str | None
    python: dict[str, Any]
    params: dict[str, Any]
    queries: dict[str, str]
    outputs: dict[str, Any]


def snapshot_user(
    *,
    username: str,
    out_dir: str | Path | None = None,
    start_ts: str | None = None,
    end_ts: str | None = None,
    margin_minutes: int = 15,
    include_market_trades: bool = True,
    include_clob_tob: bool = True,
    clickhouse_host: str | None = None,
    clickhouse_port: int | None = None,
    clickhouse_database: str | None = None,
    clickhouse_user: str | None = None,
    clickhouse_password: str | None = None,
) -> dict[str, Any]:
    """
    Snapshots ClickHouse data for a single user into versioned Parquet files with a manifest.
    """
    clickhouse_connect, pd, _ = _require_imports()

    host = clickhouse_host or os.getenv("CLICKHOUSE_HOST", "127.0.0.1")
    port = int(clickhouse_port or os.getenv("CLICKHOUSE_PORT", "8123"))
    database = clickhouse_database or os.getenv("CLICKHOUSE_DATABASE", "polybot")
    user = clickhouse_user or os.getenv("CLICKHOUSE_USER", "intellij")
    password = clickhouse_password if clickhouse_password is not None else os.getenv("CLICKHOUSE_PASSWORD", "")

    created_at = _iso_now_utc()
    snapshot_id = f"{username}-{created_at.replace(':', '').replace('-', '')}"

    base_dir = Path(out_dir) if out_dir is not None else _repo_root() / "research" / "data" / "snapshots"
    snapshot_dir = base_dir / snapshot_id
    snapshot_dir.mkdir(parents=True, exist_ok=False)

    client = clickhouse_connect.get_client(host=host, port=port, username=user, password=password, database=database)

    ch_version = client.command("SELECT version()").strip()
    as_of_ch = client.command("SELECT toString(now64(3))").strip()

    where = [f"username = {_sql_quote(username)}"]
    if start_ts:
        where.append(f"ts >= parseDateTime64BestEffort({_sql_quote(start_ts)})")
    if end_ts:
        where.append(f"ts < parseDateTime64BestEffort({_sql_quote(end_ts)})")

    trade_query = f"""
    SELECT *
    FROM {database}.user_trade_research
    WHERE {' AND '.join(where)}
    ORDER BY ts ASC
    """.strip()

    trades_df = client.query_df(trade_query)
    if trades_df.empty:
        raise RuntimeError(f"No trades found for username={username}")

    # Pandas sometimes returns tz-naive timestamps; keep as ISO strings in manifest for stability.
    min_trade_ts = pd.to_datetime(trades_df["ts"]).min()
    max_trade_ts = pd.to_datetime(trades_df["ts"]).max()
    margin = timedelta(minutes=max(0, int(margin_minutes)))

    min_ctx = (min_trade_ts - margin).to_pydatetime().replace(tzinfo=timezone.utc).isoformat(timespec="seconds")
    max_ctx = (max_trade_ts + margin).to_pydatetime().replace(tzinfo=timezone.utc).isoformat(timespec="seconds")

    trades_path = snapshot_dir / "trades.parquet"
    trades_df.to_parquet(trades_path, index=False)

    outputs: dict[str, Any] = {
        "trades": {
            "path": str(trades_path),
            "rows": int(len(trades_df)),
            "min_ts": str(min_trade_ts),
            "max_ts": str(max_trade_ts),
        }
    }

    market_trades_query = ""
    if include_market_trades:
        token_ids = sorted({str(t) for t in trades_df["token_id"].dropna().unique().tolist()})
        token_in = _sql_in(token_ids)
        market_trades_query = f"""
        SELECT *
        FROM {database}.market_trades
        WHERE ts >= parseDateTime64BestEffort({_sql_quote(min_ctx)})
          AND ts <= parseDateTime64BestEffort({_sql_quote(max_ctx)})
          AND token_id IN {token_in}
        ORDER BY ts ASC
        """.strip()
        market_trades_df = client.query_df(market_trades_query)
        market_trades_path = snapshot_dir / "market_trades.parquet"
        market_trades_df.to_parquet(market_trades_path, index=False)
        outputs["market_trades"] = {"path": str(market_trades_path), "rows": int(len(market_trades_df))}

    clob_tob_query = ""
    if include_clob_tob:
        clob_tob_query = f"""
        SELECT *
        FROM {database}.clob_tob
        WHERE username = {_sql_quote(username)}
          AND trade_at >= parseDateTime64BestEffort({_sql_quote(min_ctx)})
          AND trade_at <= parseDateTime64BestEffort({_sql_quote(max_ctx)})
        ORDER BY captured_at ASC
        """.strip()
        clob_tob_df = client.query_df(clob_tob_query)
        clob_tob_path = snapshot_dir / "clob_tob.parquet"
        clob_tob_df.to_parquet(clob_tob_path, index=False)
        outputs["clob_tob"] = {"path": str(clob_tob_path), "rows": int(len(clob_tob_df))}

    manifest = SnapshotManifest(
        snapshot_id=snapshot_id,
        created_at_utc=created_at,
        username=username,
        clickhouse={
            "host": host,
            "port": port,
            "database": database,
            "user": user,
            "version": ch_version,
            "as_of": as_of_ch,
        },
        git_sha=_git_sha(),
        python={
            "version": platform.python_version(),
            "implementation": platform.python_implementation(),
        },
        params={
            "start_ts": start_ts,
            "end_ts": end_ts,
            "margin_minutes": margin_minutes,
            "include_market_trades": include_market_trades,
            "include_clob_tob": include_clob_tob,
            "context_min_ts": min_ctx,
            "context_max_ts": max_ctx,
        },
        queries={
            "trades": trade_query,
            "market_trades": market_trades_query,
            "clob_tob": clob_tob_query,
        },
        outputs=outputs,
    )

    manifest_path = snapshot_dir / "manifest.json"
    manifest_path.write_text(json.dumps(asdict(manifest), indent=2, sort_keys=True), encoding="utf-8")

    return {
        "snapshot_dir": str(snapshot_dir),
        "manifest_path": str(manifest_path),
        "outputs": outputs,
    }


if __name__ == "__main__":
    raise SystemExit(
        "Run this via Jupyter (see research/notebooks/01_extract_snapshot.ipynb) "
        "or import snapshot_user() from research/snapshot.py"
    )

