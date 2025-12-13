package com.polybot.hft.polymarket.http;

public interface RequestRateLimiter {

  static RequestRateLimiter noop() {
    return () -> {
    };
  }

  void acquire();
}

