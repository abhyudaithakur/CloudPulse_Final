package edu.cloudpulse.api.monitoring;

import edu.cloudpulse.api.detection.PipelineSnapshot;
import edu.cloudpulse.api.detection.RuleSnapshot;
import edu.cloudpulse.api.domain.MetricUnit;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Derives a service's health from rule snapshots and active incidents. Order matters: missing
 * telemetry is reported as UNKNOWN before anything else, because a service cannot be called
 * healthy (or degraded "now") without current evidence (UC-01 alternative flow, NFR-04).
 */
public final class HealthCalculator {

  private HealthCalculator() {}

  /**
   * @param rules snapshots of the rules that monitor this service
   * @param activeRuleIds rule ids with an ACTIVE incident for this service
   */
  public static ServiceHealth healthOf(
      PipelineSnapshot.Status pipeline,
      Collection<RuleSnapshot> rules,
      Set<String> activeRuleIds) {
    if (pipeline == PipelineSnapshot.Status.STARTING) {
      return ServiceHealth.STARTING;
    }
    if (pipeline == PipelineSnapshot.Status.UNAVAILABLE) {
      return ServiceHealth.UNKNOWN;
    }
    boolean availabilityIncident =
        rules.stream()
            .anyMatch(r -> r.unit() == MetricUnit.BOOLEAN && activeRuleIds.contains(r.ruleId()));
    if (availabilityIncident) {
      return ServiceHealth.UNAVAILABLE;
    }
    if (!activeRuleIds.isEmpty()) {
      return ServiceHealth.DEGRADED;
    }
    boolean anyUnevaluable = rules.stream().anyMatch(r -> !r.phase().isEvaluable());
    return anyUnevaluable ? ServiceHealth.UNKNOWN : ServiceHealth.HEALTHY;
  }

  /**
   * The system's headline health: the worst service wins, so one unavailable or degraded service
   * is never hidden by a healthy one.
   */
  public static ServiceHealth overall(List<ServiceHealth> services) {
    List<ServiceHealth> order =
        List.of(
            ServiceHealth.HEALTHY,
            ServiceHealth.STARTING,
            ServiceHealth.UNKNOWN,
            ServiceHealth.DEGRADED,
            ServiceHealth.UNAVAILABLE);
    return services.stream()
        .max((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)))
        .orElse(ServiceHealth.STARTING);
  }
}
