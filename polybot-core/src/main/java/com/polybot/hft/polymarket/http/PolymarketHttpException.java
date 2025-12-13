package com.polybot.hft.polymarket.http;

import java.net.URI;
import java.util.Objects;

public final class PolymarketHttpException extends RuntimeException {

  private final String method;
  private final URI uri;
  private final int statusCode;
  private final String responseSnippet;

  public PolymarketHttpException(String method, URI uri, int statusCode, String responseBody) {
    super("HTTP " + statusCode + " from " + method + " " + uri + ": " + truncate(responseBody));
    this.method = Objects.requireNonNull(method, "method");
    this.uri = Objects.requireNonNull(uri, "uri");
    this.statusCode = statusCode;
    this.responseSnippet = truncate(responseBody);
  }

  public String method() {
    return method;
  }

  public URI uri() {
    return uri;
  }

  public int statusCode() {
    return statusCode;
  }

  public String responseSnippet() {
    return responseSnippet;
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000) + "...";
  }
}

