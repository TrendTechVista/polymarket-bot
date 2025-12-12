package com.polymarket.hft.polymarket.crypto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.polymarket.model.SignedOrder;

public final class OrderTypedDataBuilder {

    public static final String DOMAIN_NAME = "Polymarket CTF Exchange";
    public static final String DOMAIN_VERSION = "1";

    private OrderTypedDataBuilder() {
    }

    public static String buildTypedDataJson(
            ObjectMapper objectMapper,
            int chainId,
            String verifyingContract,
            SignedOrder order
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("primaryType", "Order");

        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("name", DOMAIN_NAME);
        domain.put("version", DOMAIN_VERSION);
        domain.put("chainId", chainId);
        domain.put("verifyingContract", verifyingContract);
        root.put("domain", domain);

        Map<String, Object> types = new LinkedHashMap<>();
        types.put("EIP712Domain", List.of(
                Map.of("name", "name", "type", "string"),
                Map.of("name", "version", "type", "string"),
                Map.of("name", "chainId", "type", "uint256"),
                Map.of("name", "verifyingContract", "type", "address")
        ));
        types.put("Order", List.of(
                Map.of("name", "salt", "type", "uint256"),
                Map.of("name", "maker", "type", "address"),
                Map.of("name", "signer", "type", "address"),
                Map.of("name", "taker", "type", "address"),
                Map.of("name", "tokenId", "type", "uint256"),
                Map.of("name", "makerAmount", "type", "uint256"),
                Map.of("name", "takerAmount", "type", "uint256"),
                Map.of("name", "expiration", "type", "uint256"),
                Map.of("name", "nonce", "type", "uint256"),
                Map.of("name", "feeRateBps", "type", "uint256"),
                Map.of("name", "side", "type", "uint8"),
                Map.of("name", "signatureType", "type", "uint8")
        ));
        root.put("types", types);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("salt", order.salt());
        message.put("maker", order.maker());
        message.put("signer", order.signer());
        message.put("taker", order.taker());
        message.put("tokenId", order.tokenId());
        message.put("makerAmount", order.makerAmount());
        message.put("takerAmount", order.takerAmount());
        message.put("expiration", order.expiration());
        message.put("nonce", order.nonce());
        message.put("feeRateBps", order.feeRateBps());
        message.put("side", order.side().toEip712Value());
        message.put("signatureType", order.signatureType());
        root.put("message", message);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize typed data JSON", e);
        }
    }
}

