# Research (Jupyter)

This folder is for offline quant research (Step 4) using ClickHouse as the source of truth.

## Prereqs

- ClickHouse running locally (`docker-compose.analytics.yaml`)
- ClickHouse DDL applied (creates `polybot.user_trade_research`):
  - `./scripts/clickhouse/apply-init.sh`

## Setup (recommended)

If you’re on Apple Silicon and see “incompatible architecture” import errors (e.g. `need 'x86_64'`),
run scripts with `arch -arm64 python3 ...` or create your venv under arm64.

### Using `uv` (recommended)

```bash
cd research
uv venv
uv pip install -r requirements.txt
```

### Using `venv` (alternative)

```bash
cd research
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Run

```bash
cd research
uv run jupyter lab
```

Open `research/notebooks/01_extract_snapshot.ipynb`.

Then:

- `research/notebooks/02_feature_layer_and_regimes.ipynb`
- `research/notebooks/03_model_and_tests.ipynb`
- `research/notebooks/04_backtest_and_montecarlo.ipynb`

## Offline snapshot report (no ClickHouse)

If you have a frozen snapshot under `research/data/snapshots/`, you can generate a quick report without ClickHouse:

```bash
cd research
python3 snapshot_report.py --snapshot data/snapshots/<snapshot-id> --write-md data/snapshots/<snapshot-id>/report.md
```

## Replication scores (ClickHouse)

### Trade-print distribution match (candidate username vs TARGET_USER)

```bash
cd research
python3 replication_score.py --candidate <your-username>
python3 replication_score.py --candidate <your-username> --start-ts '2025-12-20 00:00:00' --end-ts '2025-12-21 00:00:00'
```

### Order-stream match for the live/paper bot (strategy decisions)

This uses `polybot.strategy_gabagool_orders` + `polybot.executor_order_status` to score our bot’s
decision stream against inferred TARGET_USER distributions:

```bash
cd research
python3 replication_score_orders.py --hours 24
python3 replication_score_orders.py --hours 6 --run-id <your-run-id>
```

### Calibrate the paper maker fill/queue model (executor.sim)

Uses our own bot’s lifecycle tables to estimate a fill-time model that makes paper execution timing match reality:

```bash
cd research
python3 calibrate_maker_fill_model.py --hours 24
python3 calibrate_maker_fill_model.py --hours 24 --run-id <your-run-id>
```

### Strict sim-trade match (paper vs TARGET_USER)

This compares *trade prints* for TARGET_USER vs our paper bot (default `polybot-sim`) in the same window.

```bash
cd research
python3 sim_trade_match_report.py --hours 6
python3 sim_trade_match_report.py --hours 24 --sim-username polybot-sim
```

## Connection defaults

The scripts/notebooks default to:

- ClickHouse HTTP: `http://127.0.0.1:8123` (or `CLICKHOUSE_URL`)
- Database: `polybot`
- User: `intellij` (no password; granted SELECT in ClickHouse init)

Override via env vars:

- `CLICKHOUSE_URL` (preferred, e.g. `http://127.0.0.1:8123`)
- `CLICKHOUSE_HOST` (fallback when `CLICKHOUSE_URL` not set; default `127.0.0.1`)
- `CLICKHOUSE_PORT` (fallback when `CLICKHOUSE_URL` not set; default `8123`)
- `CLICKHOUSE_DATABASE` (default `polybot`)
- `CLICKHOUSE_USER` (default `intellij`)
- `CLICKHOUSE_PASSWORD` (default empty)
- `CLICKHOUSE_TIMEOUT_SECONDS` (default `30`)
