package com.polybot.hft.polymarket.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolyHmacSignerTests {

  @Test
  void matchesOfficialClientTestVector() {
    String signature = PolyHmacSigner.sign(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        1_000_000,
        "test-sign",
        "/orders",
        "{\"hash\": \"0x123\"}"
    );
    assertThat(signature).isEqualTo("ZwAdJKvoYRlEKDkNMwd5BuwNNtg93kNaR_oU2HrfVvc=");
  }
}

