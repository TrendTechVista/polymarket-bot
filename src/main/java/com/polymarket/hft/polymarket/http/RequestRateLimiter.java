package com.polymarket.hft.polymarket.http;

public interface RequestRateLimiter {

  void acquire();

  static RequestRateLimiter noop() {
    return () -> {
    };
  }
}

