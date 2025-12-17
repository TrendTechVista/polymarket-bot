package com.polybot.hft.events;

public final class HftEventTypes {

  private HftEventTypes() {
  }

  public static final String MARKET_WS_TOB = "market_ws.tob";

  public static final String STRATEGY_GABAGOOL_ORDER = "strategy.gabagool.order";

  public static final String EXECUTOR_ORDER_LIMIT = "executor.order.limit";
  public static final String EXECUTOR_ORDER_MARKET = "executor.order.market";
  public static final String EXECUTOR_ORDER_CANCEL = "executor.order.cancel";
}

