package com.polymarket.hft.polymarket.crypto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ClobAuthMessageBuilder {

    public static final String DOMAIN_NAME = "ClobAuthDomain";
    public static final String DOMAIN_VERSION = "1";
    public static final String MSG_TO_SIGN = "This message attests that I control the given wallet";

    private ClobAuthMessageBuilder() {
    }

    public static String buildTypedDataJson(
            ObjectMapper objectMapper,
            int chainId,
            String address,
            long timestampSeconds,
            long nonce
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("primaryType", "ClobAuth");

        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", DOMAIN_NAME);
        domain.put("version", DOMAIN_VERSION);
        domain.put("chainId", chainId);
        root.put("domain", domain);

        Map<String, Object> types = new LinkedHashMap<>();
        types.put("EIP712Domain", List.of(
                Map.of("name", "name", "type", "string"),
                Map.of("name", "version", "type", "string"),
                Map.of("name", "chainId", "type", "uint256")
        ));
        types.put("ClobAuth", List.of(
                Map.of("name", "address", "type", "address"),
                Map.of("name", "timestamp", "type", "string"),
                Map.of("name", "nonce", "type", "uint256"),
                Map.of("name", "message", "type", "string")
        ));
        root.put("types", types);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("address", address);
        message.put("timestamp", Long.toString(timestampSeconds));
        message.put("nonce", Long.toString(nonce));
        message.put("message", MSG_TO_SIGN);
        root.put("message", message);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize typed data JSON", e);
        }
    }
}

