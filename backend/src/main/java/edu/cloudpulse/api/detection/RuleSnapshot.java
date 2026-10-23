package edu.cloudpulse.api.detection;

import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.telemetry.TelemetryReading;
import edu.cloudpulse.api.telemetry.TelemetryStatus;
import java.time.Instant;

/**
 * Immutable, API-facing view of a rule after its latest evaluation. {@code value} is null whenever
 * the latest telemetry had no usable measurement, so a stale number is never shown as current.
 */
public record RuleSnapshot(
    String key,
    String ruleId,
    String serviceName,
    String title,
    MetricUnit unit,
    Comparison comparison,
    double threshold,
    long sustainSeconds,
    long recoverySeconds,
    RulePhase phase,
    Double value,
    TelemetryStatus telemetryStatus,
    String detail,
    Instant evaluatedAt,
    Instant breachStartedAt,
    Instant recoveryStartedAt) {

  static RuleSnapshot starting(RuleDefinition rule) {
    return new RuleSnapshot(
        rule.key(),
        rule.id(),
        rule.serviceName(),
        rule.title(),
        rule.unit(),
        rule.comparison(),
        rule.threshold(),
        rule.sustain().toSeconds(),
        rule.recovery().toSeconds(),
        RulePhase.STARTING,
        null,
        null,
        "not evaluated yet",
        null,
        null,
        null);
  }

  static RuleSnapshot of(
      RuleDefinition rule, Evaluation evaluation, TelemetryReading reading, Instant now) {
    return new RuleSnapshot(
        rule.key(),
        rule.id(),
        rule.serviceName(),
        rule.title(),
        rule.unit(),
        rule.comparison(),
        rule.threshold(),
        rule.sustain().toSeconds(),
        rule.recovery().toSeconds(),
        evaluation.phase(),
        evaluation.value(),
        reading.status(),
        reading.detail(),
        now,
        evaluation.breachStartedAt(),
        evaluation.recoveryStartedAt());
  }
}
