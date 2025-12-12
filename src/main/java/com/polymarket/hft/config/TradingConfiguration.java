package com.polymarket.hft.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TradingConfiguration {

    @Bean(destroyMethod = "close")
    public ExecutorService tradingExecutor() {
        // Virtual threads minimize scheduling overhead for latency-sensitive workflows.
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}

