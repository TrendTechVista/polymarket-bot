package com.polybot.ingestor.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EvictingMap<K, V> {

  private final int maxSize;
  private final LinkedHashMap<K, V> map;

  public EvictingMap(int maxSize) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be > 0");
    }
    this.maxSize = maxSize;
    this.map = new LinkedHashMap<>(Math.min(maxSize, 16), 0.75f, false);
  }

  public synchronized V get(K key) {
    if (key == null) {
      return null;
    }
    return map.get(key);
  }

  public synchronized void put(K key, V value) {
    if (key == null) {
      return;
    }
    map.put(key, value);
    if (map.size() > maxSize) {
      K eldest = map.keySet().iterator().next();
      map.remove(eldest);
    }
  }

  public synchronized int size() {
    return map.size();
  }

  public synchronized Map<K, V> snapshot() {
    return Map.copyOf(map);
  }
}

