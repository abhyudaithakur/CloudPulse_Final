package edu.cloudpulse.api.domain;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * A service CloudPulse monitors.
 *
 * @param name service name; also its Prometheus job and metric {@code application} label
 * @param url base URL of the service (its demo fault controls)
 * @param dependsOn services this one calls directly (the dependency map used for FR-08)
 * @param deployment Kubernetes deployment that runs it (the restart runbook's target, FR-10)
 */
public record MonitoredService(String name, URI url, List<String> dependsOn, String deployment) {

  public MonitoredService {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a monitored service needs a name");
    }
    Objects.requireNonNull(url, "service " + name + ": url is required");
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    deployment = deployment == null || deployment.isBlank() ? name : deployment;
  }
}
