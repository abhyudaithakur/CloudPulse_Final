package edu.cloudpulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CloudPulse API. Packages follow the RAD's backend modules: {@code telemetry} (Prometheus
 * client), {@code detection} (rules and windows), {@code incident} (lifecycle, evidence,
 * deduplication), {@code audit} (append-only history), {@code monitoring} (service health and
 * dashboard), {@code demo} (fault controls).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CloudPulseApplication {

  public static void main(String[] args) {
    SpringApplication.run(CloudPulseApplication.class, args);
  }
}
