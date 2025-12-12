package com.polymarket.hft.polymarket.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PolyHmacSigner {

    private PolyHmacSigner() {
    }

    public static String sign(String secretBase64, long timestampSeconds, String method, String requestPath, String body) {
        String message = Long.toString(timestampSeconds) + method + requestPath + (body == null ? "" : body);

        byte[] secretBytes = decodePolymarketSecret(secretBase64);
        byte[] macBytes = hmacSha256(secretBytes, message.getBytes(StandardCharsets.UTF_8));

        String sig = Base64.getEncoder().encodeToString(macBytes);
        return sig.replace('+', '-').replace('/', '_');
    }

    private static byte[] decodePolymarketSecret(String secretBase64) {
        if (secretBase64 == null) {
            throw new IllegalArgumentException("secret must not be null");
        }
        String sanitized = secretBase64
                .replace('-', '+')
                .replace('_', '/')
                .replaceAll("[^A-Za-z0-9+/=]", "");

        // Pad to a multiple of 4 for Java's Base64 decoder.
        int remainder = sanitized.length() % 4;
        if (remainder != 0) {
            sanitized = sanitized + "=".repeat(4 - remainder);
        }

        return Base64.getDecoder().decode(sanitized);
    }

    private static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}

