package com.polybot.ingestor.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EvictingKeySet {

  private final int maxSize;
  private final LinkedHashMap<String, Boolean> map;

  public EvictingKeySet(int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be > 0");
    }
    this.maxSize = maxSize;
    this.map = new LinkedHashMap<>(Math.min(maxSize, 16), 0.75f, false);
  }

  public synchronized boolean add(String key) {
    if (key == null) {
      return false;
    }
    String k = key.trim();
    if (k.isEmpty()) {
      return false;
    }
    if (map.containsKey(k)) {
      return false;
    }
    map.put(k, Boolean.TRUE);
    if (map.size() > maxSize) {
      String eldest = map.keySet().iterator().next();
      map.remove(eldest);
    }
    return true;
  }

  public synchronized int size() {
    return map.size();
  }

  public synchronized Map<String, Boolean> snapshot() {
    return Map.copyOf(map);
  }
}

