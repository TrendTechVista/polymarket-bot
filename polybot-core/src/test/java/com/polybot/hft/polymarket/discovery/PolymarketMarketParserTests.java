package com.polybot.hft.polymarket.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PolymarketMarketParserTests {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void yesNoTokens_readsArrays() throws Exception {
    JsonNode market = objectMapper.readTree("""
        {
          "question": "Will BTC be up in 15 minutes?",
          "outcomes": ["Yes", "No"],
          "clobTokenIds": ["111", "222"]
        }
        """);

    assertThat(PolymarketMarketParser.yesNoTokens(market, objectMapper))
        .get()
        .isEqualTo(new YesNoTokens("111", "222"));
  }

  @Test
  void yesNoTokens_readsJsonStringFields() throws Exception {
    JsonNode market = objectMapper.readTree("""
        {
          "question": "Will ETH be up in 15 minutes?",
          "outcomes": "[\\"Yes\\",\\"No\\"]",
          "clobTokenIds": "[\\"333\\",\\"444\\"]"
        }
        """);

    assertThat(PolymarketMarketParser.yesNoTokens(market, objectMapper))
        .get()
        .isEqualTo(new YesNoTokens("333", "444"));
  }

  @Test
  void extractMarkets_fromEventsPayload() throws Exception {
    JsonNode root = objectMapper.readTree("""
        {
          "events": [
            {
              "id": "e1",
              "markets": [
                {"question":"Q1","outcomes":["Yes","No"],"clobTokenIds":["1","2"]},
                {"question":"Q2","outcomes":["Yes","No"],"clobTokenIds":["3","4"]}
              ]
            }
          ]
        }
        """);

    List<JsonNode> markets = PolymarketMarketParser.extractMarkets(root);
    assertThat(markets).hasSize(2);
    assertThat(PolymarketMarketParser.question(markets.getFirst())).isEqualTo("Q1");
  }
}

