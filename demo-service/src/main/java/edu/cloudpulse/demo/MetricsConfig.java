package edu.cloudpulse.demo;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metric shape CloudPulse's PromQL depends on, set in code rather than properties so it is explicit
 * and survives Spring Boot property renames: every meter is tagged {@code application=<service
 * name>} (checkout-api or payment-api), and HTTP server requests publish histogram buckets
 * (needed for histogram_quantile).
 */
@Configuration
public class MetricsConfig {

  @Bean
  MeterFilter applicationTag(@Value("${cloudpulse.demo.service-name:checkout-api}") String service) {
    return MeterFilter.commonTags(Tags.of("application", service));
  }

  @Bean
  MeterFilter requestLatencyHistogram() {
    return new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
        if (id.getName().equals("http.server.requests")) {
          return DistributionStatisticConfig.builder().percentilesHistogram(true).build().merge(config);
        }
        return config;
      }
    };
  }
}
