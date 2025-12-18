package com.polybot.ingestor.config;

import com.polybot.hft.polymarket.ws.ClobMarketWebSocketClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ClobMarketWebSocketClient.class)
public class MarketWsConfiguration {
}

