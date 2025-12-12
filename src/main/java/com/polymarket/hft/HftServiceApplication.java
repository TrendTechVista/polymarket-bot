package com.polymarket.hft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HftServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HftServiceApplication.class, args);
    }
}
