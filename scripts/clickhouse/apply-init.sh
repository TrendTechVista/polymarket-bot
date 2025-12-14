#!/usr/bin/env bash
set -euo pipefail

CLICKHOUSE_HTTP_URL="${CLICKHOUSE_HTTP_URL:-http://127.0.0.1:8123}"
CLICKHOUSE_CONTAINER="${CLICKHOUSE_CONTAINER:-polybot-clickhouse}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

INIT_DIR="${CLICKHOUSE_INIT_DIR:-${REPO_ROOT}/analytics-service/clickhouse/init}"

if [[ ! -d "${INIT_DIR}" ]]; then
  echo "ClickHouse init directory not found: ${INIT_DIR}" >&2
  exit 1
fi

shopt -s nullglob
sql_files=("${INIT_DIR}"/*.sql)
shopt -u nullglob

if [[ ${#sql_files[@]} -eq 0 ]]; then
  echo "No .sql files found under: ${INIT_DIR}" >&2
  exit 1
fi

echo "Applying ClickHouse init DDL from ${INIT_DIR}"

if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -qx "${CLICKHOUSE_CONTAINER}"; then
  echo "Using docker container: ${CLICKHOUSE_CONTAINER}"
  for sql_file in "${sql_files[@]}"; do
    echo "-> ${sql_file}"
    docker exec -i "${CLICKHOUSE_CONTAINER}" clickhouse-client --multiquery <"${sql_file}" >/dev/null
  done
  echo "Done."
  exit 0
fi

echo "Docker container not running; using ClickHouse HTTP API: ${CLICKHOUSE_HTTP_URL}"

for sql_file in "${sql_files[@]}"; do
  echo "-> ${sql_file}"
  python3 - "${CLICKHOUSE_HTTP_URL}" "${sql_file}" <<'PY'
import sys
import urllib.request

url = sys.argv[1].rstrip("/")
path = sys.argv[2]

with open(path, "r", encoding="utf-8") as f:
    raw = f.read()

lines = []
for line in raw.splitlines():
    stripped = line.strip()
    if not stripped:
        continue
    if stripped.startswith("--"):
        continue
    lines.append(line)

sql = "\n".join(lines)
statements = [s.strip() for s in sql.split(";") if s.strip()]

for statement in statements:
    req = urllib.request.Request(
        url + "/",
        data=(statement + "\n").encode("utf-8"),
        method="POST",
    )
    try:
        with urllib.request.urlopen(req) as resp:
            resp.read()
    except Exception as e:
        raise RuntimeError(f"Failed statement from {path}: {statement[:200]}") from e
PY
done

echo "Done."
