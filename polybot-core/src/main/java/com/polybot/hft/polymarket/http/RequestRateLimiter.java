package com.polybot.hft.polymarket.http;

public interface RequestRateLimiter {

  void acquire();

  static RequestRateLimiter noop() {
    return () -> {
    };
  }
}

