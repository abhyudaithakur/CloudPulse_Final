package edu.cloudpulse.api.config;

import edu.cloudpulse.api.domain.MonitoredService;
import java.net.URI;
import java.util.List;

/**
 * One entry of {@code cloudpulse.services}: a monitored service, where its fault controls live,
 * which services it calls, and its Kubernetes deployment (the remediation target).
 */
public record ServiceProperties(String name, URI url, List<String> dependsOn, String deployment) {

  public MonitoredService toService() {
    return new MonitoredService(
        name, url, dependsOn == null ? List.of() : dependsOn, deployment == null ? name : deployment);
  }
}
