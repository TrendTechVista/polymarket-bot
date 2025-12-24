#!/usr/bin/env python3
"""
GABAGOOL22 STRATEGY BACKTESTER
==============================
A proper replay system that:
1. Loads historical market data + target trader trades
2. Cleans and validates data quality
3. Replays events in chronological order
4. Applies our strategy logic to each event
5. Compares our simulated trades to target trader's actual trades
6. Generates a replication score report
"""

import os
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Optional, Dict, List, Tuple
from decimal import Decimal, ROUND_DOWN
import json

import clickhouse_connect
import pandas as pd
import numpy as np

# =============================================================================
# CONFIGURATION
# =============================================================================

@dataclass
class StrategyConfig:
    """Matches application-develop.yaml gabagool config"""
    min_complete_set_edge: float = 0.01      # 1% minimum edge
    min_seconds_to_end: int = 0
    max_seconds_to_end: int = 3600           # 1 hour max
    improve_ticks: int = 0                    # Quote AT best bid (maker)
    tick_size: float = 0.01

    # Discrete sizing by series + time-to-end bucket (medians from latest snapshot analysis).
    def replica_shares(self, series: str, seconds_to_end: int) -> float:
        s = int(seconds_to_end)

        # 15m (BTC):  <1m=11, 1-3m=13, 3-5m=17, 5-10m=19, 10-15m=20
        if series == 'btc-15m':
            if s < 60:
                return 11.0
            if s < 180:
                return 13.0
            if s < 300:
                return 17.0
            if s < 600:
                return 19.0
            return 20.0

        # 15m (ETH):  <1m=8, 1-3m=10, 3-5m=12, 5-10m=13, 10-15m=14
        if series == 'eth-15m':
            if s < 60:
                return 8.0
            if s < 180:
                return 10.0
            if s < 300:
                return 12.0
            if s < 600:
                return 13.0
            return 14.0

        # 1h (BTC): <1m=9, 1-3m=10, 3-5m=11, 5-10m=12, 10-15m=14, 15-20m=15, 20-30m=17, 30-60m=18
        if series == 'btc-1h':
            if s < 60:
                return 9.0
            if s < 180:
                return 10.0
            if s < 300:
                return 11.0
            if s < 600:
                return 12.0
            if s < 900:
                return 14.0
            if s < 1200:
                return 15.0
            if s < 1800:
                return 17.0
            return 18.0

        # 1h (ETH): <1m=7, 1-5m=8, 5-10m=9, 10-15m=11, 15-20m=12, 20-30m=13, 30-60m=14
        if series == 'eth-1h':
            if s < 60:
                return 7.0
            if s < 300:
                return 8.0
            if s < 600:
                return 9.0
            if s < 900:
                return 11.0
            if s < 1200:
                return 12.0
            if s < 1800:
                return 13.0
            return 14.0

        return 15.0

@dataclass
class TopOfBook:
    """Book state for one token"""
    best_bid: Optional[float]
    best_bid_size: Optional[float]
    best_ask: Optional[float]
    best_ask_size: Optional[float]
    mid: Optional[float]
    timestamp: Optional[datetime]

    @property
    def is_valid(self) -> bool:
        return (self.best_bid is not None and self.best_bid > 0 and
                self.best_ask is not None and self.best_ask > 0)


@dataclass
class MarketState:
    """Complete market state for a UP/DOWN pair"""
    market_slug: str
    series: str
    seconds_to_end: int
    up_token_id: str
    down_token_id: str
    up_tob: TopOfBook
    down_tob: TopOfBook

    @property
    def complete_set_edge(self) -> Optional[float]:
        """Edge from complete set: 1 - (bid_up + bid_down)"""
        if not self.up_tob.is_valid or not self.down_tob.is_valid:
            return None
        return 1.0 - self.up_tob.best_bid - self.down_tob.best_bid

    @property
    def has_both_tob(self) -> bool:
        return self.up_tob.is_valid and self.down_tob.is_valid


@dataclass
class GabagoolTrade:
    """A single target trader trade"""
    ts: datetime
    market_slug: str
    series: str
    token_id: str
    other_token_id: str
    outcome: str  # 'Up' or 'Down'
    side: str     # 'BUY' or 'SELL'
    price: float
    size: float
    seconds_to_end: int
    realized_pnl: Optional[float]
    settle_price: Optional[float]

    # Book state at trade time
    our_tob: TopOfBook
    other_tob: TopOfBook


@dataclass
class SimulatedOrder:
    """What our strategy would have done"""
    would_quote: bool
    reason: str
    quote_price: Optional[float] = None
    quote_size: Optional[float] = None
    complete_set_edge: Optional[float] = None


@dataclass
class TradeComparison:
    """Comparison of target trader trade vs our simulated response"""
    gabagool_trade: GabagoolTrade
    our_response: SimulatedOrder

    # Match analysis
    would_match: bool = False
    price_diff: Optional[float] = None
    size_diff: Optional[float] = None
    simulated_pnl: Optional[float] = None

    @property
    def match_type(self) -> str:
        if not self.our_response.would_quote:
            return self.our_response.reason
        if self.would_match:
            return 'MATCH'
        return 'WOULD_QUOTE_NO_FILL'


# =============================================================================
# DATA LOADING & CLEANING
# =============================================================================

class DataPipeline:
    """Load and clean data from ClickHouse"""

    def __init__(self, host: str = 'localhost', port: int = 8123):
        self.client = clickhouse_connect.get_client(host=host, port=port)

    def load_gabagool_trades(self,
                             start_time: Optional[datetime] = None,
                             end_time: Optional[datetime] = None) -> pd.DataFrame:
        """Load target trader trades with all required fields"""

        where_clauses = ["username = os.getenv('POLYMARKET_TARGET_USER', 'TARGET_USER')"]
        if start_time:
            where_clauses.append(f"ts >= '{start_time.isoformat()}'")
        if end_time:
            where_clauses.append(f"ts <= '{end_time.isoformat()}'")

        where_sql = " AND ".join(where_clauses)

        query = f"""
        SELECT
            ts,
            market_slug,
            token_id,
            outcome,
            side,
            price,
            size,
            seconds_to_end,
            is_resolved,
            settle_price,
            realized_pnl,

            -- Our side TOB (prefer WS, fallback to REST)
            coalesce(ws_best_bid_price, best_bid_price) as our_best_bid,
            coalesce(ws_best_bid_size, best_bid_size) as our_best_bid_size,
            coalesce(ws_best_ask_price, best_ask_price) as our_best_ask,
            coalesce(ws_best_ask_size, best_ask_size) as our_best_ask_size,
            coalesce(ws_mid, mid) as our_mid,
            coalesce(ws_tob_captured_at, tob_captured_at) as our_tob_time,

            -- Token IDs for both sides
            token_ids,

            -- Series classification
            multiIf(
                market_slug LIKE 'btc-updown-15m-%', 'btc-15m',
                market_slug LIKE 'eth-updown-15m-%', 'eth-15m',
                market_slug LIKE 'bitcoin-up-or-down-%', 'btc-1h',
                market_slug LIKE 'ethereum-up-or-down-%', 'eth-1h',
                'other'
            ) as series

        FROM polybot.user_trade_enriched_v3
        WHERE {where_sql}
          AND (market_slug LIKE '%updown%' OR market_slug LIKE '%up-or-down%')
        ORDER BY ts
        """

        df = self.client.query_df(query)
        print(f"Loaded {len(df)} target trader trades")
        return df

    def load_market_ws_tob(self,
                           asset_ids: List[str],
                           start_time: Optional[datetime] = None,
                           end_time: Optional[datetime] = None) -> pd.DataFrame:
        """Load WS TOB data for specific assets"""

        if not asset_ids:
            return pd.DataFrame()

        asset_list = ",".join([f"'{a}'" for a in asset_ids])

        where_clauses = [f"asset_id IN ({asset_list})"]
        if start_time:
            where_clauses.append(f"ts >= '{start_time.isoformat()}'")
        if end_time:
            where_clauses.append(f"ts <= '{end_time.isoformat()}'")

        where_sql = " AND ".join(where_clauses)

        query = f"""
        SELECT
            ts,
            asset_id,
            best_bid_price,
            best_bid_size,
            best_ask_price,
            best_ask_size,
            (best_bid_price + best_ask_price) / 2 as mid
        FROM polybot.market_ws_tob
        WHERE {where_sql}
        ORDER BY asset_id, ts
        """

        df = self.client.query_df(query)
        print(f"Loaded {len(df)} WS TOB records for {len(asset_ids)} assets")
        return df

    def clean_trades(self, df: pd.DataFrame) -> Tuple[pd.DataFrame, Dict]:
        """
        Clean and validate trade data.
        Returns: (cleaned_df, quality_report)
        """
        quality_report = {
            'total_input': len(df),
            'issues': {},
        }

        # Filter out 'other' series
        mask_series = df['series'] != 'other'
        quality_report['issues']['wrong_series'] = (~mask_series).sum()

        # Require valid seconds_to_end
        mask_time = df['seconds_to_end'].notna() & (df['seconds_to_end'] >= 0)
        quality_report['issues']['invalid_time_to_end'] = (~mask_time).sum()

        # Require token_ids array
        mask_tokens = df['token_ids'].apply(lambda x: isinstance(x, (list, tuple)) and len(x) == 2)
        quality_report['issues']['missing_token_ids'] = (~mask_tokens).sum()

        # Require our side TOB
        mask_our_tob = df['our_best_bid'].notna() & (df['our_best_bid'] > 0)
        quality_report['issues']['missing_our_tob'] = (~mask_our_tob).sum()

        # Combined mask
        combined_mask = mask_series & mask_time & mask_tokens & mask_our_tob

        clean_df = df[combined_mask].copy()
        quality_report['total_clean'] = len(clean_df)
        quality_report['clean_rate'] = len(clean_df) / len(df) * 100 if len(df) > 0 else 0

        print(f"\nData Quality Report:")
        print(f"  Input trades: {quality_report['total_input']}")
        print(f"  Clean trades: {quality_report['total_clean']} ({quality_report['clean_rate']:.1f}%)")
        print(f"  Issues:")
        for issue, count in quality_report['issues'].items():
            print(f"    - {issue}: {count}")

        return clean_df, quality_report


# =============================================================================
# STRATEGY REPLAY ENGINE
# =============================================================================

class StrategyReplayEngine:
    """
    Replays our strategy logic against historical data.
    For each target trader trade, determines what our strategy would have done.
    """

    def __init__(self, config: StrategyConfig):
        self.config = config
        self.tob_cache: Dict[str, List[Tuple[datetime, TopOfBook]]] = {}

    def load_other_side_tob(self, pipeline: DataPipeline, trades_df: pd.DataFrame):
        """
        Load WS TOB data for the 'other' token in each trade.
        This is needed to calculate complete-set edge.
        """
        # Get unique other token IDs
        other_token_ids = set()
        for _, row in trades_df.iterrows():
            token_ids = row['token_ids']
            if not isinstance(token_ids, (list, tuple)) or len(token_ids) != 2:
                continue

            outcome = row['outcome']
            other_id = token_ids[1] if outcome == 'Up' else token_ids[0]
            other_token_ids.add(other_id)

        if not other_token_ids:
            print("No other token IDs to load")
            return

        # Load WS TOB for these tokens
        start_time = trades_df['ts'].min() - timedelta(hours=1)
        end_time = trades_df['ts'].max() + timedelta(hours=1)

        tob_df = pipeline.load_market_ws_tob(
            list(other_token_ids),
            start_time,
            end_time
        )

        # Build cache: asset_id -> [(ts, tob), ...]
        for asset_id in other_token_ids:
            asset_tob = tob_df[tob_df['asset_id'] == asset_id].sort_values('ts')
            self.tob_cache[asset_id] = [
                (row['ts'], TopOfBook(
                    best_bid=row['best_bid_price'],
                    best_bid_size=row['best_bid_size'],
                    best_ask=row['best_ask_price'],
                    best_ask_size=row['best_ask_size'],
                    mid=row['mid'],
                    timestamp=row['ts']
                ))
                for _, row in asset_tob.iterrows()
            ]

        print(f"Loaded TOB cache for {len(self.tob_cache)} other tokens")

    def get_other_tob_at_time(self, token_id: str, ts: datetime) -> TopOfBook:
        """Get the most recent TOB for a token at a given time (ASOF join)"""
        if token_id not in self.tob_cache:
            return TopOfBook(None, None, None, None, None, None)

        cache = self.tob_cache[token_id]
        if not cache:
            return TopOfBook(None, None, None, None, None, None)

        # Binary search for most recent TOB <= ts
        best_tob = None
        for tob_ts, tob in cache:
            if tob_ts <= ts:
                best_tob = tob
            else:
                break

        return best_tob if best_tob else TopOfBook(None, None, None, None, None, None)

    def evaluate_trade(self, trade: GabagoolTrade) -> SimulatedOrder:
        """
        Apply our strategy logic to a trade.
        Returns what our strategy would have done.
        """
        config = self.config

        # 1) Check time window
        if trade.seconds_to_end < config.min_seconds_to_end:
            return SimulatedOrder(False, 'BEFORE_TIME_WINDOW')
        if trade.seconds_to_end > config.max_seconds_to_end:
            return SimulatedOrder(False, 'AFTER_TIME_WINDOW')

        # 2) Check our side TOB
        if not trade.our_tob.is_valid:
            return SimulatedOrder(False, 'NO_OUR_TOB')

        # 3) Check other side TOB
        if not trade.other_tob.is_valid:
            return SimulatedOrder(False, 'NO_OTHER_TOB')

        # 4) Calculate complete-set edge
        if trade.outcome == 'Up':
            edge = 1.0 - trade.our_tob.best_bid - trade.other_tob.best_bid
        else:
            edge = 1.0 - trade.other_tob.best_bid - trade.our_tob.best_bid

        if edge < config.min_complete_set_edge:
            return SimulatedOrder(False, 'INSUFFICIENT_EDGE', complete_set_edge=edge)

        # 5) Determine quote price (AT best bid, no improvement)
        quote_price = trade.our_tob.best_bid

        # 6) Determine quote size (time-to-end adjusted, series-specific)
        quote_size = config.replica_shares(trade.series, trade.seconds_to_end)

        # (Directional bias is disabled - market neutral)

        return SimulatedOrder(
            would_quote=True,
            reason='WOULD_QUOTE',
            quote_price=quote_price,
            quote_size=quote_size,
            complete_set_edge=edge
        )

    def compare_trade(self, trade: GabagoolTrade, our_response: SimulatedOrder) -> TradeComparison:
        """
        Compare target trader's actual trade to our simulated response.
        Determine if we would have matched.
        """
        comparison = TradeComparison(
            gabagool_trade=trade,
            our_response=our_response
        )

        if not our_response.would_quote:
            return comparison

        # Would we have filled?
        # If target trader filled at or better than our quote, we likely would too
        if trade.side == 'BUY':
            # For BUY, target trader filled at trade.price
            # Our quote would be at our_response.quote_price
            # We'd fill if our quote >= his fill (we're willing to pay at least as much)
            comparison.would_match = our_response.quote_price >= trade.price - 0.01
        else:
            # For SELL, similar logic
            comparison.would_match = our_response.quote_price <= trade.price + 0.01

        comparison.price_diff = trade.price - our_response.quote_price
        comparison.size_diff = trade.size - our_response.quote_size

        # Simulate PnL if we matched
        if comparison.would_match and trade.settle_price is not None:
            comparison.simulated_pnl = (trade.settle_price - our_response.quote_price) * our_response.quote_size

        return comparison


# =============================================================================
# BACKTEST RUNNER
# =============================================================================

class Backtester:
    """Main backtest orchestrator"""

    def __init__(self, config: StrategyConfig = None):
        self.config = config or StrategyConfig()
        self.pipeline = DataPipeline()
        self.engine = StrategyReplayEngine(self.config)

        self.trades: List[GabagoolTrade] = []
        self.comparisons: List[TradeComparison] = []

    def run(self,
            start_time: Optional[datetime] = None,
            end_time: Optional[datetime] = None) -> Dict:
        """
        Run the full backtest.
        Returns a results dictionary with metrics and comparisons.
        """
        print("=" * 60)
        print("GABAGOOL22 STRATEGY BACKTEST")
        print("=" * 60)

        # 1) Load and clean data
        print("\n[1/4] Loading data...")
        raw_df = self.pipeline.load_gabagool_trades(start_time, end_time)
        clean_df, quality_report = self.pipeline.clean_trades(raw_df)

        if len(clean_df) == 0:
            print("No clean trades to backtest!")
            return {'error': 'No clean trades'}

        # 2) Load other-side TOB data
        print("\n[2/4] Loading other-side TOB data...")
        self.engine.load_other_side_tob(self.pipeline, clean_df)

        # 3) Convert to trade objects and replay
        print("\n[3/4] Replaying strategy...")
        self.trades = []
        self.comparisons = []

        for _, row in clean_df.iterrows():
            # Build trade object
            token_ids = row['token_ids']
            outcome = row['outcome']
            other_token_id = token_ids[1] if outcome == 'Up' else token_ids[0]

            our_tob = TopOfBook(
                best_bid=row['our_best_bid'],
                best_bid_size=row['our_best_bid_size'],
                best_ask=row['our_best_ask'],
                best_ask_size=row['our_best_ask_size'],
                mid=row['our_mid'],
                timestamp=row['our_tob_time']
            )

            other_tob = self.engine.get_other_tob_at_time(other_token_id, row['ts'])

            trade = GabagoolTrade(
                ts=row['ts'],
                market_slug=row['market_slug'],
                series=row['series'],
                token_id=row['token_id'],
                other_token_id=other_token_id,
                outcome=outcome,
                side=row['side'],
                price=row['price'],
                size=row['size'],
                seconds_to_end=int(row['seconds_to_end']),
                realized_pnl=row['realized_pnl'] if pd.notna(row['realized_pnl']) else None,
                settle_price=row['settle_price'] if pd.notna(row['settle_price']) else None,
                our_tob=our_tob,
                other_tob=other_tob
            )

            self.trades.append(trade)

            # Evaluate with our strategy
            our_response = self.engine.evaluate_trade(trade)

            # Compare
            comparison = self.engine.compare_trade(trade, our_response)
            self.comparisons.append(comparison)

        # 4) Generate report
        print("\n[4/4] Generating report...")
        report = self._generate_report(quality_report)

        return report

    def _generate_report(self, quality_report: Dict) -> Dict:
        """Generate comprehensive backtest report"""

        total = len(self.comparisons)
        if total == 0:
            return {'error': 'No comparisons'}

        # Count match types
        match_types = {}
        for c in self.comparisons:
            mt = c.match_type
            match_types[mt] = match_types.get(mt, 0) + 1

        matches = [c for c in self.comparisons if c.match_type == 'MATCH']
        would_quote = [c for c in self.comparisons if c.our_response.would_quote]

        # Calculate metrics
        report = {
            'data_quality': quality_report,
            'summary': {
                'total_trades': total,
                'we_would_quote': len(would_quote),
                'we_would_match': len(matches),
                'quote_rate_pct': len(would_quote) / total * 100,
                'match_rate_pct': len(matches) / total * 100,
                'fill_rate_if_quoted_pct': len(matches) / len(would_quote) * 100 if would_quote else 0,
            },
            'match_type_breakdown': match_types,
            'by_series': {},
        }

        # Price accuracy when matching
        if matches:
            price_diffs = [c.price_diff for c in matches if c.price_diff is not None]
            if price_diffs:
                report['price_accuracy'] = {
                    'avg_price_diff': np.mean(price_diffs),
                    'median_price_diff': np.median(price_diffs),
                    'std_price_diff': np.std(price_diffs),
                }

        # Size accuracy when matching
        if matches:
            size_ratios = [c.gabagool_trade.size / c.our_response.quote_size
                          for c in matches if c.our_response.quote_size]
            if size_ratios:
                report['size_accuracy'] = {
                    'avg_size_ratio': np.mean(size_ratios),
                    'median_size_ratio': np.median(size_ratios),
                }

        # PnL comparison
        gabagool_pnl = sum(c.gabagool_trade.realized_pnl or 0 for c in self.comparisons)
        matched_gabagool_pnl = sum(c.gabagool_trade.realized_pnl or 0 for c in matches)
        simulated_pnl = sum(c.simulated_pnl or 0 for c in matches)

        report['pnl'] = {
            'gabagool_total_pnl': gabagool_pnl,
            'gabagool_pnl_on_matches': matched_gabagool_pnl,
            'our_simulated_pnl': simulated_pnl,
        }

        # By series breakdown
        for series in ['btc-15m', 'eth-15m', 'btc-1h', 'eth-1h']:
            series_comparisons = [c for c in self.comparisons if c.gabagool_trade.series == series]
            series_matches = [c for c in series_comparisons if c.match_type == 'MATCH']

            if series_comparisons:
                report['by_series'][series] = {
                    'total': len(series_comparisons),
                    'matches': len(series_matches),
                    'match_rate_pct': len(series_matches) / len(series_comparisons) * 100,
                    'avg_gabagool_size': np.mean([c.gabagool_trade.size for c in series_matches]) if series_matches else 0,
                    'our_avg_modeled_size': np.mean([
                        self.config.replica_shares(series, int(c.gabagool_trade.seconds_to_end))
                        for c in series_matches
                    ]) if series_matches else 0,
                }

        return report

    def print_report(self, report: Dict):
        """Pretty print the backtest report"""

        print("\n" + "=" * 60)
        print("BACKTEST RESULTS")
        print("=" * 60)

        if 'error' in report:
            print(f"Error: {report['error']}")
            return

        # Summary
        s = report['summary']
        print(f"\n{'SUMMARY':^60}")
        print("-" * 60)
        print(f"Total target trader trades:     {s['total_trades']:,}")
        print(f"We would quote:              {s['we_would_quote']:,} ({s['quote_rate_pct']:.1f}%)")
        print(f"We would match:              {s['we_would_match']:,} ({s['match_rate_pct']:.1f}%)")
        print(f"Fill rate if quoted:         {s['fill_rate_if_quoted_pct']:.1f}%")

        # Match type breakdown
        print(f"\n{'MATCH TYPE BREAKDOWN':^60}")
        print("-" * 60)
        for mt, count in sorted(report['match_type_breakdown'].items(), key=lambda x: -x[1]):
            pct = count / s['total_trades'] * 100
            print(f"  {mt:30} {count:6,} ({pct:5.1f}%)")

        # Price accuracy
        if 'price_accuracy' in report:
            pa = report['price_accuracy']
            print(f"\n{'PRICE ACCURACY (when matching)':^60}")
            print("-" * 60)
            print(f"  Average price diff:        {pa['avg_price_diff']:+.4f}")
            print(f"  Median price diff:         {pa['median_price_diff']:+.4f}")
            print(f"  Std dev:                   {pa['std_price_diff']:.4f}")

        # Size accuracy
        if 'size_accuracy' in report:
            sa = report['size_accuracy']
            print(f"\n{'SIZE ACCURACY (when matching)':^60}")
            print("-" * 60)
            print(f"  Average size ratio:        {sa['avg_size_ratio']:.2f}x")
            print(f"  Median size ratio:         {sa['median_size_ratio']:.2f}x")

        # PnL
        pnl = report['pnl']
        print(f"\n{'PNL COMPARISON':^60}")
        print("-" * 60)
        print(f"  Gabagool total PnL:        ${pnl['gabagool_total_pnl']:,.2f}")
        print(f"  Gabagool PnL on matches:   ${pnl['gabagool_pnl_on_matches']:,.2f}")
        print(f"  Our simulated PnL:         ${pnl['our_simulated_pnl']:,.2f}")

        # By series
        print(f"\n{'BY MARKET SERIES':^60}")
        print("-" * 60)
        print(f"{'Series':<12} {'Trades':>8} {'Matches':>8} {'Rate':>8} {'G-Size':>8} {'Our-Size':>10}")
        print("-" * 60)
        for series, data in report['by_series'].items():
            print(f"{series:<12} {data['total']:>8,} {data['matches']:>8,} "
                  f"{data['match_rate_pct']:>7.1f}% {data['avg_gabagool_size']:>8.1f} {data['our_base_size']:>10.1f}")

        print("\n" + "=" * 60)


# =============================================================================
# MAIN
# =============================================================================

def main():
    """Run backtest"""

    config = StrategyConfig(
        min_complete_set_edge=0.01,  # 1% minimum edge
        min_seconds_to_end=0,
        max_seconds_to_end=3600,
        improve_ticks=0,  # AT best bid
    )

    backtester = Backtester(config)

    # Run backtest on all available data
    report = backtester.run()

    # Print results
    backtester.print_report(report)

    # Save report to JSON
    output_path = os.path.join(os.path.dirname(__file__), 'backtest_report.json')

    # Convert datetime objects for JSON serialization
    def json_serial(obj):
        if isinstance(obj, datetime):
            return obj.isoformat()
        if isinstance(obj, np.floating):
            return float(obj)
        if isinstance(obj, np.integer):
            return int(obj)
        raise TypeError(f"Type {type(obj)} not serializable")

    with open(output_path, 'w') as f:
        json.dump(report, f, indent=2, default=json_serial)
    print(f"\nReport saved to: {output_path}")

    return report


if __name__ == '__main__':
    main()
