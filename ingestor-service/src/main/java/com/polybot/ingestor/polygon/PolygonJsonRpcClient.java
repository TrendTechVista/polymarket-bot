package com.polybot.ingestor.polygon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
@Slf4j
public class PolygonJsonRpcClient {

  private final @NonNull RestClient rpc;
  private final @NonNull ObjectMapper objectMapper;

  public PolygonJsonRpcClient(
      @Qualifier("polygonRpcRestClient") RestClient rpc,
      ObjectMapper objectMapper
  ) {
    this.rpc = rpc;
    this.objectMapper = objectMapper;
  }

  public JsonNode getTransactionReceipt(String txHash) {
    ArrayNode params = objectMapper.createArrayNode().add(txHash);
    return call("eth_getTransactionReceipt", params);
  }

  public JsonNode getBlockByNumber(String blockNumberHex) {
    ArrayNode params = objectMapper.createArrayNode().add(blockNumberHex).add(false);
    return call("eth_getBlockByNumber", params);
  }

  private JsonNode call(String method, ArrayNode params) {
    ObjectNode req = objectMapper.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", 1);
    req.put("method", method);
    req.set("params", params);

    int maxAttempts = 3;
    Duration backoff = Duration.ofMillis(150);
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        String body = rpc.post()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(req.toString())
            .retrieve()
            .body(String.class);

        if (body == null || body.isBlank()) {
          throw new RuntimeException("polygon rpc empty response method=%s".formatted(method));
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode err = root.path("error");
        if (err != null && !err.isMissingNode() && !err.isNull()) {
          throw new RuntimeException("polygon rpc error method=%s error=%s".formatted(method, err.toString()));
        }
        return root.path("result");
      } catch (Exception e) {
        if (attempt == maxAttempts) {
          if (e instanceof RuntimeException re) {
            throw re;
          }
          throw new RuntimeException("polygon rpc failed method=%s".formatted(method), e);
        }
        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          if (e instanceof RuntimeException re) {
            throw re;
          }
          throw new RuntimeException("polygon rpc interrupted method=%s".formatted(method), e);
        }
        backoff = backoff.multipliedBy(2);
      }
    }

    throw new IllegalStateException("unreachable");
  }
}
