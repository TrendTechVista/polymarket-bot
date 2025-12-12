package com.polymarket.hft.polymarket.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.polymarket.hft.domain.OrderSide;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Credentials;

class OrderEip712SignatureTests {

    @Test
    void matchesOfficialOrderUtilsTestVector() {
        String privateKey = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
        Credentials creds = Credentials.create(privateKey.substring(2));
        String address = creds.getAddress();

        String sig = Eip712Signer.signOrder(
                creds,
                80002,
                "0xdFE02Eb6733538f8Ea35D585af8DE5958AD99E40",
                "479249096354",
                address,
                address,
                "0x0000000000000000000000000000000000000000",
                "1234",
                "100000000",
                "50000000",
                "0",
                "0",
                "100",
                OrderSide.BUY.toEip712Value(),
                0
        );

        assertThat(sig).isEqualTo(
                "0x302cd9abd0b5fcaa202a344437ec0b6660da984e24ae9ad915a592a90facf5a51bb8a873cd8d270f070217fea1986531d5eec66f1162a81f66e026db653bf7ce1c"
        );
    }
}
