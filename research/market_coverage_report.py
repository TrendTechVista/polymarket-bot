#!/usr/bin/env python3
"""
Market coverage report: how many active gab markets our bot actually touched.

This is the primary driver of strict match recall. If we don't place any orders
in a market instance, none of gab's trades there can ever match.
"""

from __future__ import annotations

import argparse
import csv
import os
from dataclasses import dataclass
from io import StringIO
from typing import Dict, List
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SERIES_WHERE = (
    "(market_slug LIKE 'btc-updown-15m-%' OR market_slug LIKE 'eth-updown-15m-%' "
    " OR market_slug LIKE 'bitcoin-up-or-down-%' OR market_slug LIKE 'ethereum-up-or-down-%')"
)


@dataclass(frozen=True)
class ClickHouseHttp:
    url: str
    database: str
    user: str
    password: str
    timeout_seconds: int

    def _post(self, sql: str) -> str:
        params = {"database": self.database}
        if self.user:
            params["user"] = self.user
        if self.password:
            params["password"] = self.password
        full = f"{self.url.rstrip('/')}/?{urlencode(params)}"
        req = Request(full, data=sql.encode("utf-8"), method="POST")
        with urlopen(req, timeout=self.timeout_seconds) as resp:
            return resp.read().decode("utf-8")

    def query_rows(self, sql: str) -> List[Dict[str, str]]:
        sql = sql.strip().rstrip(";") + "\nFORMAT CSVWithNames"
        text = self._post(sql)
        if not text.strip():
            return []
        reader = csv.DictReader(StringIO(text))
        return [dict(r) for r in reader]


def _time_where(col: str, start_ts: str | None, end_ts: str | None, hours: int) -> str:
    if start_ts or end_ts:
        parts = []
        if start_ts:
            parts.append(f"{col} >= parseDateTime64BestEffort('{start_ts}')")
        if end_ts:
            parts.append(f"{col} < parseDateTime64BestEffort('{end_ts}')")
        return " AND " + " AND ".join(parts) if parts else ""
    return f" AND {col} >= now() - INTERVAL {int(hours)} HOUR"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gab-username", default=os.getenv("POLYMARKET_TARGET_USER", "TARGET_USER"))
    ap.add_argument("--hours", type=int, default=24)
    ap.add_argument("--start-ts", default=None)
    ap.add_argument("--end-ts", default=None)
    ap.add_argument("--top-missing", type=int, default=10)
    args = ap.parse_args()

    ch = ClickHouseHttp(
        url=(
            os.getenv("CLICKHOUSE_URL")
            or f"http://{os.getenv('CLICKHOUSE_HOST', '127.0.0.1')}:{os.getenv('CLICKHOUSE_PORT', '8123')}"
        ),
        database=os.getenv("CLICKHOUSE_DATABASE", "polybot"),
        user=os.getenv("CLICKHOUSE_USER", "intellij"),
        password=os.getenv("CLICKHOUSE_PASSWORD", ""),
        timeout_seconds=int(os.getenv("CLICKHOUSE_TIMEOUT_SECONDS", "30")),
    )

    where = _time_where("ts", args.start_ts, args.end_ts, args.hours)

    gab_rows = ch.query_rows(
        f"""
        SELECT market_slug, count() AS trades
        FROM polybot.user_trades_dedup
        WHERE username = '{args.gab_username}'
          AND {SERIES_WHERE}
          {where}
        GROUP BY market_slug
        """
    )

    bot_rows = ch.query_rows(
        f"""
        SELECT market_slug, count() AS orders
        FROM polybot.strategy_gabagool_orders
        WHERE action = 'PLACE'
          AND success = 1
          AND market_slug != ''
          {where}
        GROUP BY market_slug
        """
    )

    gab_counts = {r["market_slug"]: int(float(r["trades"])) for r in gab_rows}
    bot_counts = {r["market_slug"]: int(float(r["orders"])) for r in bot_rows}

    gab_markets = set(gab_counts)
    bot_markets = set(bot_counts)
    overlap = gab_markets & bot_markets

    gab_total_trades = sum(gab_counts.values())
    gab_trades_in_overlap = sum(gab_counts[m] for m in overlap)

    overlap_pct = 0.0 if not gab_markets else (len(overlap) * 100.0 / len(gab_markets))
    trade_overlap_pct = 0.0 if gab_total_trades == 0 else (gab_trades_in_overlap * 100.0 / gab_total_trades)

    print(f"Window: hours={args.hours} start={args.start_ts or '(auto)'} end={args.end_ts or '(auto)'}")
    print(f"Gab markets: {len(gab_markets):,} | Bot markets: {len(bot_markets):,} | Overlap: {len(overlap):,} ({overlap_pct:.2f}%)")
    print(f"Gab trades: {gab_total_trades:,} | Gab trades in overlap: {gab_trades_in_overlap:,} ({trade_overlap_pct:.2f}%)")

    missing = [(m, gab_counts[m]) for m in gab_markets - bot_markets]
    missing.sort(key=lambda x: x[1], reverse=True)
    if args.top_missing and missing:
        print(f"\nTop missing markets (n={min(args.top_missing, len(missing))})")
        print("market_slug\tgab_trades")
        for m, cnt in missing[: args.top_missing]:
            print(f"{m}\t{cnt}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
