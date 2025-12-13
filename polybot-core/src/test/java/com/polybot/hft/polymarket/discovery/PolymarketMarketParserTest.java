package com.polybot.hft.polymarket.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PolymarketMarketParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void yesNoTokens_supportsUpDownOutcomesFromGammaStrings() throws Exception {
    JsonNode market = objectMapper.readTree("""
        {
          "active": true,
          "outcomes": "[\\"Up\\", \\"Down\\"]",
          "clobTokenIds": "[\\"111\\", \\"222\\"]"
        }
        """);

    Optional<YesNoTokens> tokens = PolymarketMarketParser.yesNoTokens(market, objectMapper);
    assertThat(tokens).isPresent();
    assertThat(tokens.get().yesTokenId()).isEqualTo("111");
    assertThat(tokens.get().noTokenId()).isEqualTo("222");
  }
}

