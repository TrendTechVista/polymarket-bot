package com.polybot.ingestor;

import com.polybot.hft.events.HftEventsConfiguration;
import com.polybot.hft.events.HftEventsProperties;
import com.polybot.hft.events.kafka.KafkaHftEventsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = {IngestorServiceApplication.class, HftEventsProperties.class})
@EnableScheduling
@Import({HftEventsConfiguration.class, KafkaHftEventsConfiguration.class})
public class IngestorServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestorServiceApplication.class, args);
  }
}
