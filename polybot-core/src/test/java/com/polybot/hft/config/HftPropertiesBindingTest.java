package com.polybot.hft.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HftPropertiesBindingTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(TestConfig.class)
      .withPropertyValues(
          "hft.mode=LIVE",
          "hft.executor.base-url=http://localhost:8080",
          "hft.polymarket.market-ws-enabled=true",
          "hft.strategy.house-edge.enabled=true",
          "hft.strategy.house-edge.discovery.enabled=true",
          "hft.strategy.house-edge.discovery.queries[0]=Bitcoin",
          "hft.strategy.house-edge.discovery.queries[1]=Ethereum"
      );

  @Test
  void bindsNestedRecordsFromRelaxedProperties() {
    runner.run(context -> {
      HftProperties properties = context.getBean(HftProperties.class);

      assertThat(properties.mode()).isEqualTo(HftProperties.TradingMode.LIVE);
      assertThat(properties.executor().baseUrl()).isEqualTo("http://localhost:8080");
      assertThat(properties.polymarket().marketWsEnabled()).isTrue();

      HftProperties.HouseEdge houseEdge = properties.strategy().houseEdge();
      assertThat(houseEdge.enabled()).isTrue();
      assertThat(houseEdge.discovery().enabled()).isTrue();
      assertThat(houseEdge.discovery().queries()).containsExactly("Bitcoin", "Ethereum");
    });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(HftProperties.class)
  static class TestConfig {
  }
}

