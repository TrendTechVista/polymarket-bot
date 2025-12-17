package com.polybot.ingestor.polygon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolygonJsonRpcClient {

  private final @NonNull @Qualifier("polygonRpcRestClient") RestClient rpc;
  private final @NonNull ObjectMapper objectMapper;

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

    String body = rpc.post()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(req.toString())
        .retrieve()
        .body(String.class);

    if (body == null || body.isBlank()) {
      throw new RuntimeException("polygon rpc empty response method=%s".formatted(method));
    }

    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode err = root.path("error");
      if (err != null && !err.isMissingNode() && !err.isNull()) {
        throw new RuntimeException("polygon rpc error method=%s error=%s".formatted(method, err.toString()));
      }
      return root.path("result");
    } catch (Exception e) {
      log.debug("polygon rpc parse failed method={} body={}", method, body);
      throw new RuntimeException("polygon rpc parse failed method=%s".formatted(method), e);
    }
  }
}

