package com.polymarket.hft.polymarket.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.hft.config.HftProperties;
import com.polymarket.hft.polymarket.clob.PolymarketClobClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PolymarketConfiguration {

    @Bean
    public PolymarketClobClient polymarketClobClient(
            HftProperties properties,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        HftProperties.Polymarket polymarket = properties.getPolymarket();
        return new PolymarketClobClient(
                URI.create(polymarket.getClobRestUrl()),
                httpClient,
                objectMapper,
                clock,
                polymarket.getChainId(),
                polymarket.isUseServerTime()
        );
    }
}

