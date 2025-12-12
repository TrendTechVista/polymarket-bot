package com.polymarket.hft.polymarket.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;

class ClobAuthEip712SignatureTests {

    @Test
    void matchesOfficialClientTestVector() {
        String privateKey = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
        Credentials creds = Credentials.create(privateKey.substring(2));
        String sig = Eip712Signer.signClobAuth(creds, 80002, 10_000_000L, 23L);

        assertThat(sig).isEqualTo(
                "0xf62319a987514da40e57e2f4d7529f7bac38f0355bd88bb5adbb3768d80de6c1682518e0af677d5260366425f4361e7b70c25ae232aff0ab2331e2b164a1aedc1b"
        );
    }
}
