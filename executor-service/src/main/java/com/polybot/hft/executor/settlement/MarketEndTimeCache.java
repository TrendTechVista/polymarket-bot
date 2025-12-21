package com.polybot.hft.executor.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketEndTimeCache {

  private final @NonNull HttpClient httpClient;
  private final @NonNull ObjectMapper objectMapper;

  private final Map<String, Instant> cache = new ConcurrentHashMap<>();
  private volatile Instant lastRefresh = Instant.EPOCH;

  /**
   * Get market end time for a condition ID. Returns null if not found or if market has no end time.
   */
  public Instant getEndTime(String conditionId) {
    if (conditionId == null || conditionId.isBlank()) {
      return null;
    }

    refreshIfStale();

    return cache.get(conditionId);
  }

  private void refreshIfStale() {
    Instant now = Instant.now();
    if (java.time.Duration.between(lastRefresh, now).toMinutes() < 5) {
      return; // Cache is fresh (< 5 minutes old)
    }

    try {
      String sql = """
          SELECT condition_id, end_date
          FROM polybot.gamma_markets_latest
          WHERE condition_id != ''
            AND end_date > now() - INTERVAL 7 DAY
          FORMAT JSONEachRow
          """;

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:8123"))
          .POST(HttpRequest.BodyPublishers.ofString(sql))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("failed to refresh market end time cache: HTTP {}", response.statusCode());
        return;
      }

      String[] lines = response.body().split("\n");
      int count = 0;
      for (String line : lines) {
        if (line.isBlank()) continue;
        try {
          JsonNode row = objectMapper.readTree(line);
          String conditionId = row.has("condition_id") ? row.get("condition_id").asText() : null;
          String endDateStr = row.has("end_date") ? row.get("end_date").asText() : null;

          if (conditionId != null && !conditionId.isBlank() && endDateStr != null && !endDateStr.isBlank()) {
            Instant endDate = Instant.parse(endDateStr.replace(' ', 'T') + "Z");
            cache.put(conditionId, endDate);
            count++;
          }
        } catch (Exception e) {
          log.debug("failed to parse market end time row: {}", e.toString());
        }
      }

      lastRefresh = now;
      log.debug("market end time cache refreshed: {} conditions", count);
    } catch (Exception e) {
      log.warn("failed to refresh market end time cache: {}", e.toString());
    }
  }
}
