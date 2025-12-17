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
          "hft.strategy.gabagool.enabled=true",
          "hft.strategy.gabagool.min-seconds-to-end=600",
          "hft.strategy.gabagool.max-seconds-to-end=900",
          "hft.strategy.gabagool.quote-size=10"
      );

  @Test
  void bindsNestedRecordsFromRelaxedProperties() {
    runner.run(context -> {
      HftProperties properties = context.getBean(HftProperties.class);

      assertThat(properties.mode()).isEqualTo(HftProperties.TradingMode.LIVE);
      assertThat(properties.executor().baseUrl()).isEqualTo("http://localhost:8080");
      assertThat(properties.polymarket().marketWsEnabled()).isTrue();

      HftProperties.Gabagool gabagool = properties.strategy().gabagool();
      assertThat(gabagool.enabled()).isTrue();
      assertThat(gabagool.minSecondsToEnd()).isEqualTo(600L);
      assertThat(gabagool.maxSecondsToEnd()).isEqualTo(900L);
      assertThat(gabagool.quoteSize().intValue()).isEqualTo(10);
    });
  }

  @Configuration(proxyBeanMethods=false)
  @EnableConfigurationProperties(HftProperties.class)
  static class TestConfig {
  }
}

