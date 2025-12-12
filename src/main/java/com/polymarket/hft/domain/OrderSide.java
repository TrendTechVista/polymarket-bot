package com.polymarket.hft.domain;

public enum OrderSide {
    BUY,
    SELL

    ;

    public int toEip712Value() {
        return this == BUY ? 0 : 1;
    }
}
