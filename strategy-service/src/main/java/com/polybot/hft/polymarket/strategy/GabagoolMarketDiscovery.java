package com.polybot.hft.polymarket.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polybot.hft.config.HftProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Discovers active Up/Down markets from Polymarket Gamma API.
 * These are the markets gabagool22 trades.
 *
 * MARKET BREAKDOWN (from 21,305 trades analysis):
 * - 15min-BTC: $1,680 PnL, 52.44% win rate (BEST PERFORMER)
 * - 15min-ETH: $544 PnL, 50.91% win rate
 * - 1hour-ETH: $162 PnL, 50.48% win rate
 * - 1hour-BTC: $93 PnL, 49.73% win rate
 *
 * Strategy: Focus on 15min-BTC but also trade other markets for diversification.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GabagoolMarketDiscovery {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    // Slug patterns for gabagool22's target markets
    // - 15min BTC: btc-updown-15m-{epoch}
    // - 15min ETH: eth-updown-15m-{epoch}
    // - 1hour BTC: bitcoin-up-or-down-{date}
    // - 1hour ETH: ethereum-up-or-down-{date}

    private final HftProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final List<DiscoveredMarket> activeMarkets = new CopyOnWriteArrayList<>();

    /**
     * Get currently active markets suitable for gabagool strategy.
     */
    public List<DiscoveredMarket> getActiveMarkets() {
        return new ArrayList<>(activeMarkets);
    }

    /**
     * Refresh market discovery every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void discoverMarkets() {
        if (!properties.strategy().gabagool().enabled()) {
            return;
        }

        log.info("GABAGOOL DISCOVERY: Scanning for active markets...");

        try {
            // Fetch all active events once and filter locally
            List<DiscoveredMarket> discovered = fetchActiveUpDownEvents();

            // Filter to only active markets within trading window
            // 15min markets: 5-20 min before end
            // 1hour markets: 10-20 min before end (same timing pattern)
            Instant now = Instant.now();
            Instant minEnd = now.plus(5, ChronoUnit.MINUTES);
            Instant maxEnd = now.plus(20, ChronoUnit.MINUTES);

            List<DiscoveredMarket> active = discovered.stream()
                    .filter(m -> m.endTime() != null)
                    .filter(m -> m.endTime().isAfter(minEnd) && m.endTime().isBefore(maxEnd))
                    .filter(m -> !m.closed())
                    .toList();

            activeMarkets.clear();
            activeMarkets.addAll(active);

            log.info("GABAGOOL DISCOVERY: Found {} total, {} in 5-20 min window", discovered.size(), active.size());

            if (!active.isEmpty()) {
                for (DiscoveredMarket m : active) {
                    long minutesToEnd = Duration.between(now, m.endTime()).toMinutes();
                    log.info("  - {} [{}] (ends in {}min)", m.slug(), m.marketType(), minutesToEnd);
                }
            } else if (!discovered.isEmpty()) {
                log.info("  (markets found but outside 5-20 min window, waiting...)");
            } else {
                log.info("  (no matching BTC/ETH Up/Down markets found from API)");
            }
        } catch (Exception e) {
            log.error("GABAGOOL DISCOVERY: Error discovering markets: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetch all active Up/Down events from Gamma API.
     *
     * Uses /markets endpoint with tag=crypto to get crypto markets,
     * then filter for BTC/ETH updown patterns.
     */
    private List<DiscoveredMarket> fetchActiveUpDownEvents() {
        List<DiscoveredMarket> markets = new ArrayList<>();
        Set<String> seenSlugs = new HashSet<>();

        try {
            // Use /markets endpoint with tag=crypto - this returns markets not events
            String listUrl = properties.polymarket().gammaUrl() + "/markets?tag=crypto&active=true&closed=false&limit=100";

            log.debug("GABAGOOL DISCOVERY: Fetching from: {}", listUrl);

            HttpRequest listRequest = HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());

            if (listResponse.statusCode() != 200) {
                log.warn("Gamma API returned status {}", listResponse.statusCode());
                return markets;
            }

            JsonNode listRoot = objectMapper.readTree(listResponse.body());
            log.debug("GABAGOOL DISCOVERY: Got {} markets from API", listRoot.size());

            List<String> sampleSlugs = new ArrayList<>();
            List<String> matchingSlugs = new ArrayList<>();

            if (listRoot.isArray()) {
                for (JsonNode marketNode : listRoot) {
                    String slug = marketNode.path("slug").asText("");

                    if (sampleSlugs.size() < 15) {
                        sampleSlugs.add(slug);
                    }

                    // Match BTC/ETH updown-15m and up-or-down patterns
                    boolean isMatch = (slug.startsWith("btc-updown-15m") ||
                                      slug.startsWith("eth-updown-15m") ||
                                      slug.startsWith("bitcoin-up-or-down") ||
                                      slug.startsWith("ethereum-up-or-down"));

                    if (!isMatch) {
                        continue;
                    }

                    matchingSlugs.add(slug);

                    if (seenSlugs.contains(slug)) {
                        continue;
                    }
                    seenSlugs.add(slug);

                    // Try to parse directly from this market node (it has clobTokenIds)
                    DiscoveredMarket market = parseMarketNode(marketNode);
                    if (market != null) {
                        markets.add(market);
                    }
                }
            }

            log.info("GABAGOOL DISCOVERY: Sample slugs: {}", sampleSlugs);
            log.info("GABAGOOL DISCOVERY: Matching BTC/ETH slugs: {}", matchingSlugs);
            log.info("GABAGOOL DISCOVERY: Successfully parsed {} markets", markets.size());

        } catch (Exception e) {
            log.warn("Failed to fetch active markets: {}", e.getMessage(), e);
        }

        return markets;
    }

    /**
     * Parse a market node directly from /markets endpoint
     */
    private DiscoveredMarket parseMarketNode(JsonNode marketNode) {
        try {
            String slug = marketNode.path("slug").asText(null);
            if (slug == null || slug.isBlank()) {
                return null;
            }

            boolean closed = marketNode.path("closed").asBoolean(false);
            if (closed) {
                return null;
            }

            // Determine market type
            String marketType;
            if (slug.contains("updown-15m")) {
                marketType = "updown-15m";
            } else if (slug.contains("up-or-down")) {
                marketType = "up-or-down";
            } else {
                return null;
            }

            // Parse end time
            Instant endTime = null;
            String endDateStr = marketNode.path("endDate").asText(null);
            if (endDateStr != null && !endDateStr.isBlank()) {
                try {
                    endTime = Instant.parse(endDateStr);
                } catch (Exception e) {
                    endTime = parseEndTimeFromSlug(slug, marketType);
                }
            } else {
                endTime = parseEndTimeFromSlug(slug, marketType);
            }

            String marketId = marketNode.path("id").asText(null);

            // Parse tokens and outcomes directly from market node
            List<String> tokenIds = parseStringArray(marketNode.path("clobTokenIds"));
            List<String> outcomes = parseStringArray(marketNode.path("outcomes"));

            String upTokenId = null;
            String downTokenId = null;

            if (tokenIds.size() >= 2 && outcomes.size() >= 2) {
                for (int i = 0; i < outcomes.size() && i < tokenIds.size(); i++) {
                    String outcome = outcomes.get(i).toLowerCase().trim();
                    String tokenId = tokenIds.get(i);

                    if (tokenId != null && !tokenId.isBlank()) {
                        if (outcome.equals("up")) {
                            upTokenId = tokenId;
                        } else if (outcome.equals("down")) {
                            downTokenId = tokenId;
                        }
                    }
                }
            }

            if (upTokenId == null || downTokenId == null) {
                log.debug("Could not find Up/Down tokens for {}", slug);
                return null;
            }

            log.debug("GABAGOOL DISCOVERY: Parsed {} -> endTime={}", slug, endTime);

            return new DiscoveredMarket(
                    slug,
                    marketId,
                    upTokenId,
                    downTokenId,
                    endTime,
                    closed,
                    marketType
            );
        } catch (Exception e) {
            log.debug("Failed to parse market: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch full market details by slug using /events?slug={slug}
     */
    private DiscoveredMarket fetchMarketBySlug(String slug) {
        try {
            String url = properties.polymarket().gammaUrl() + "/events?slug=" + slug;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty()) {
                return null;
            }

            JsonNode eventNode = root.get(0);
            return parseEventWithFullDetails(eventNode, slug);

        } catch (Exception e) {
            log.debug("Error fetching market {}: {}", slug, e.getMessage());
            return null;
        }
    }

    /**
     * Parse event with full market details (from /events?slug= query)
     */
    private DiscoveredMarket parseEventWithFullDetails(JsonNode eventNode, String slug) {
        try {
            String eventSlug = eventNode.path("slug").asText(null);
            if (eventSlug == null || eventSlug.isBlank()) {
                return null;
            }

            boolean closed = eventNode.path("closed").asBoolean(false);
            if (closed) {
                return null;
            }

            // Determine market type
            String marketType;
            if (eventSlug.contains("updown-15m")) {
                marketType = "updown-15m";
            } else if (eventSlug.contains("up-or-down")) {
                marketType = "up-or-down";
            } else {
                return null;
            }

            // Parse end time
            Instant endTime = null;
            String endDateStr = eventNode.path("endDate").asText(null);
            if (endDateStr != null && !endDateStr.isBlank()) {
                try {
                    endTime = Instant.parse(endDateStr);
                } catch (Exception e) {
                    endTime = parseEndTimeFromSlug(eventSlug, marketType);
                }
            } else {
                endTime = parseEndTimeFromSlug(eventSlug, marketType);
            }

            // Get markets array - this should have full details when queried by slug
            JsonNode marketsArray = eventNode.path("markets");
            if (!marketsArray.isArray() || marketsArray.isEmpty()) {
                log.debug("Event {} has no markets array", eventSlug);
                return null;
            }

            String upTokenId = null;
            String downTokenId = null;
            String marketId = null;

            // The first market should have the tokens
            JsonNode firstMarket = marketsArray.get(0);
            marketId = firstMarket.path("id").asText(null);

            // Parse clobTokenIds and outcomes - they can be either:
            // 1. A JSON array: ["token1", "token2"]
            // 2. A string containing JSON: "[\"token1\", \"token2\"]"
            List<String> tokenIds = parseStringArray(firstMarket.path("clobTokenIds"));
            List<String> outcomes = parseStringArray(firstMarket.path("outcomes"));

            if (tokenIds.size() >= 2 && outcomes.size() >= 2) {
                for (int i = 0; i < outcomes.size() && i < tokenIds.size(); i++) {
                    String outcome = outcomes.get(i).toLowerCase().trim();
                    String tokenId = tokenIds.get(i);

                    if (tokenId != null && !tokenId.isBlank()) {
                        if (outcome.equals("up")) {
                            upTokenId = tokenId;
                        } else if (outcome.equals("down")) {
                            downTokenId = tokenId;
                        }
                    }
                }
            }

            if (upTokenId == null || downTokenId == null) {
                log.debug("Could not find Up/Down tokens for {}: outcomes={} tokens={}", eventSlug, outcomes, tokenIds);
                return null;
            }

            log.info("GABAGOOL DISCOVERY: Parsed {} -> endTime={}", eventSlug, endTime);

            return new DiscoveredMarket(
                    eventSlug,
                    marketId,
                    upTokenId,
                    downTokenId,
                    endTime,
                    closed,
                    marketType
            );
        } catch (Exception e) {
            log.debug("Failed to parse event {}: {}", slug, e.getMessage());
            return null;
        }
    }

    /**
     * Parse a JSON node that can be either:
     * 1. A JSON array: ["a", "b"]
     * 2. A string containing a JSON array: "[\"a\", \"b\"]"
     */
    private List<String> parseStringArray(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }

        // Case 1: Already a JSON array
        if (node.isArray()) {
            List<String> result = new ArrayList<>(node.size());
            for (JsonNode n : node) {
                if (n != null && !n.isNull()) {
                    String s = n.asText(null);
                    if (s != null) {
                        result.add(s);
                    }
                }
            }
            return result;
        }

        // Case 2: A string containing a JSON array
        if (node.isTextual()) {
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                return parseStringArray(parsed);
            } catch (Exception e) {
                return List.of();
            }
        }

        return List.of();
    }

    private Instant parseEndTimeFromSlug(String slug, String marketType) {
        try {
            if (marketType.equals("updown-15m")) {
                // Slug format: btc-updown-15m-1734364800
                // The last part is the epoch timestamp, add 15 min for end time
                String[] parts = slug.split("-");
                if (parts.length >= 4) {
                    String epochStr = parts[parts.length - 1];
                    long epoch = Long.parseLong(epochStr);
                    return Instant.ofEpochSecond(epoch + 900); // +15 minutes
                }
            } else {
                // 1hour markets: bitcoin-up-or-down-december-16-8am-et
                // These don't have epoch in slug, need to use endDate from API
                // Return null to rely on API endDate field
                return null;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public record DiscoveredMarket(
            String slug,
            String marketId,
            String upTokenId,
            String downTokenId,
            Instant endTime,
            boolean closed,
            String marketType
    ) {}
}

