package com.polybot.hft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExecutorServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExecutorServiceApplication.class, args);
  }
}
