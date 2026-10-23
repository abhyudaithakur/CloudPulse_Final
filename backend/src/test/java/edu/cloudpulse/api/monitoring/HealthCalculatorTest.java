package edu.cloudpulse.api.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cloudpulse.api.detection.PipelineSnapshot.Status;
import edu.cloudpulse.api.detection.RulePhase;
import edu.cloudpulse.api.detection.RuleSnapshot;
import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthCalculatorTest {

  private static RuleSnapshot rule(String id, MetricUnit unit, RulePhase phase) {
    return new RuleSnapshot(
        "checkout-api/" + id, id, "checkout-api", id, unit, Comparison.ABOVE, 1.0, 30, 10, phase,
        phase.isEvaluable() ? 0.5 : null, null, "", null, null, null);
  }

  private final List<RuleSnapshot> healthyRules =
      List.of(
          rule("LATENCY_P95_SUSTAINED", MetricUnit.SECONDS, RulePhase.HEALTHY),
          rule("TARGET_AVAILABILITY", MetricUnit.BOOLEAN, RulePhase.HEALTHY));

  @Test
  @DisplayName("UC-01: all rules evaluable and no incidents is HEALTHY")
  void uc01_healthy() {
    assertThat(HealthCalculator.healthOf(Status.CONNECTED, healthyRules, Set.of()))
        .isEqualTo(ServiceHealth.HEALTHY);
  }

  @Test
  @DisplayName("NFR-04 / UC-01 alt: telemetry down is UNKNOWN, even with an incident open")
  void nfr04_telemetryDownIsUnknown() {
    assertThat(HealthCalculator.healthOf(Status.UNAVAILABLE, healthyRules, Set.of()))
        .isEqualTo(ServiceHealth.UNKNOWN);
    assertThat(
            HealthCalculator.healthOf(
                Status.UNAVAILABLE, healthyRules, Set.of("LATENCY_P95_SUSTAINED")))
        .isEqualTo(ServiceHealth.UNKNOWN);
  }

  @Test
  @DisplayName("NFR-04: a rule with no data makes health UNKNOWN rather than HEALTHY")
  void nfr04_noDataIsUnknown() {
    List<RuleSnapshot> rules =
        List.of(
            rule("LATENCY_P95_SUSTAINED", MetricUnit.SECONDS, RulePhase.NO_DATA),
            rule("TARGET_AVAILABILITY", MetricUnit.BOOLEAN, RulePhase.HEALTHY));

    assertThat(HealthCalculator.healthOf(Status.CONNECTED, rules, Set.of()))
        .isEqualTo(ServiceHealth.UNKNOWN);
  }

  @Test
  @DisplayName("FR-06: a latency or error incident is DEGRADED; availability is UNAVAILABLE")
  void fr06_incidentsDegradeOrMarkUnavailable() {
    assertThat(
            HealthCalculator.healthOf(Status.CONNECTED, healthyRules, Set.of("LATENCY_P95_SUSTAINED")))
        .isEqualTo(ServiceHealth.DEGRADED);
    assertThat(
            HealthCalculator.healthOf(Status.CONNECTED, healthyRules, Set.of("TARGET_AVAILABILITY")))
        .isEqualTo(ServiceHealth.UNAVAILABLE);
  }

  @Test
  @DisplayName("UC-01: before the first evaluation the service is STARTING, not HEALTHY")
  void uc01_startingBeforeFirstCycle() {
    assertThat(HealthCalculator.healthOf(Status.STARTING, healthyRules, Set.of()))
        .isEqualTo(ServiceHealth.STARTING);
  }

  @Test
  @DisplayName("FR-06: overall health is the worst service, so one bad service is never hidden")
  void fr06_overallIsWorstService() {
    assertThat(HealthCalculator.overall(List.of(ServiceHealth.HEALTHY, ServiceHealth.HEALTHY)))
        .isEqualTo(ServiceHealth.HEALTHY);
    assertThat(HealthCalculator.overall(List.of(ServiceHealth.HEALTHY, ServiceHealth.DEGRADED)))
        .isEqualTo(ServiceHealth.DEGRADED);
    assertThat(HealthCalculator.overall(List.of(ServiceHealth.UNAVAILABLE, ServiceHealth.UNKNOWN)))
        .isEqualTo(ServiceHealth.UNAVAILABLE);
    assertThat(HealthCalculator.overall(List.of(ServiceHealth.HEALTHY, ServiceHealth.UNKNOWN)))
        .isEqualTo(ServiceHealth.UNKNOWN);
  }
}
