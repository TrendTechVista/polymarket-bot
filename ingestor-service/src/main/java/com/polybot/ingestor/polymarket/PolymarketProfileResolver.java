package com.polybot.ingestor.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketProfileResolver {

  private static final Pattern NEXT_DATA = Pattern.compile("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
  private static final Pattern ETH_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");

  private final @NonNull @Qualifier("polymarketSiteRestClient") RestClient polymarketSiteRestClient;
  private final @NonNull ObjectMapper objectMapper;

  public Optional<PolymarketUserIdentity> resolve(String username) {
    String sanitized = sanitizeUsername(username);
    if (sanitized == null) {
      return Optional.empty();
    }

    String html = polymarketSiteRestClient.get()
        .uri("/@{username}?tab=activity", sanitized)
        .retrieve()
        .body(String.class);

    if (html == null || html.isBlank()) {
      return Optional.empty();
    }

    Matcher matcher = NEXT_DATA.matcher(html);
    if (!matcher.find()) {
      log.warn("Could not find __NEXT_DATA__ in profile html username={}", sanitized);
      return Optional.empty();
    }

    String json = matcher.group(1);
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode pageProps = root.path("props").path("pageProps");

      String proxyAddress = textOrNull(pageProps.path("proxyAddress"));
      String primaryAddress = textOrNull(pageProps.path("primaryAddress"));
      String baseAddress = textOrNull(pageProps.path("baseAddress"));

      String best = firstValidAddress(proxyAddress, primaryAddress, baseAddress);
      if (best == null) {
        log.warn("Could not resolve address from profile __NEXT_DATA__ username={}", sanitized);
        return Optional.empty();
      }

      return Optional.of(new PolymarketUserIdentity(sanitized, baseAddress, proxyAddress, primaryAddress));
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing profile __NEXT_DATA__ username=%s".formatted(sanitized), e);
    }
  }

  private static String sanitizeUsername(String username) {
    if (username == null) {
      return null;
    }
    String sanitized = username.trim();
    if (sanitized.isEmpty()) {
      return null;
    }
    if (sanitized.startsWith("@")) {
      sanitized = sanitized.substring(1);
    }
    return sanitized;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String v = node.asText(null);
    if (v == null || v.isBlank()) {
      return null;
    }
    return v.trim();
  }

  private static String firstValidAddress(String... candidates) {
    if (candidates == null) {
      return null;
    }
    for (String c : candidates) {
      if (c == null) {
        continue;
      }
      String v = c.trim();
      if (ETH_ADDRESS.matcher(v).matches()) {
        return v;
      }
    }
    return null;
  }

  public record PolymarketUserIdentity(
      String username,
      String baseAddress,
      String proxyAddress,
      String primaryAddress
  ) {
  }
}
