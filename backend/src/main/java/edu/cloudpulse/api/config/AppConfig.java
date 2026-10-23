package edu.cloudpulse.api.config;

import edu.cloudpulse.api.detection.RuleCatalog;
import edu.cloudpulse.api.detection.SustainedThresholdEvaluator;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.remediation.DisabledWorkloadClient;
import edu.cloudpulse.api.remediation.KubernetesWorkloadClient;
import edu.cloudpulse.api.remediation.WorkloadClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

  /** Injected everywhere time matters, so tests can drive windows with a synthetic clock. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public ServiceTopology serviceTopology(CloudPulseProperties properties) {
    return new ServiceTopology(
        properties.services().stream().map(ServiceProperties::toService).toList());
  }

  /** Every rule template applied to every monitored service (NFR-10). */
  @Bean
  public RuleCatalog ruleCatalog(CloudPulseProperties properties, ServiceTopology topology) {
    List<RuleDefinition> rules = new ArrayList<>();
    for (String service : topology.names()) {
      for (RuleProperties template : properties.ruleTemplates()) {
        rules.add(template.toDefinition(service));
      }
    }
    return new RuleCatalog(rules);
  }

  @Bean
  public SustainedThresholdEvaluator sustainedThresholdEvaluator(CloudPulseProperties properties) {
    return new SustainedThresholdEvaluator(properties.detection().maxSampleGap());
  }

  /**
   * The only component that can change the cluster. Without {@code cloudpulse.remediation.enabled}
   * (Docker Compose mode) it is a stub that refuses every call.
   */
  @Bean
  public WorkloadClient workloadClient(CloudPulseProperties properties) {
    CloudPulseProperties.Remediation remediation = properties.remediation();
    return remediation.enabled()
        ? new KubernetesWorkloadClient(remediation.kubernetes())
        : new DisabledWorkloadClient();
  }
}
