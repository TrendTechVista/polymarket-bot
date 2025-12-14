package com.polybot.hft.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class HftEventsConfiguration {

  @Bean
  @ConditionalOnProperty(prefix="hft.events", name="enabled", havingValue="false", matchIfMissing=true)
  @ConditionalOnMissingBean(HftEventPublisher.class)
  public HftEventPublisher noopHftEventPublisher() {
    return new NoopHftEventPublisher();
  }
}
