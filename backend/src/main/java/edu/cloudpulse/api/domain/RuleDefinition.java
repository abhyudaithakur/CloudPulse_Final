package edu.cloudpulse.api.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * A deterministic detection rule: a PromQL query that must return a single instant value, a
 * threshold, and the windows that turn a raw violation into an incident and back.
 *
 * @param id rule type, also the incident "type"; unique together with the service (FR-04)
 * @param serviceName monitored service the rule describes
 * @param title human-readable incident title
 * @param query PromQL expression evaluated each cycle; must aggregate to one series
 * @param comparison direction of violation
 * @param threshold violation threshold in {@code unit}
 * @param unit measurement unit
 * @param sustain how long the violation must hold continuously before an incident opens (FR-03)
 * @param recovery how long the value must stay healthy before the incident resolves (UC-05)
 * @param severityPolicy how severity is derived from the observed value
 */
public record RuleDefinition(
    String id,
    String serviceName,
    String title,
    String query,
    Comparison comparison,
    double threshold,
    MetricUnit unit,
    Duration sustain,
    Duration recovery,
    SeverityPolicy severityPolicy) {

  public RuleDefinition {
    requireText(id, "id");
    requireText(serviceName, "service");
    requireText(title, "title");
    requireText(query, "query");
    Objects.requireNonNull(comparison, "rule " + id + ": comparison is required");
    Objects.requireNonNull(unit, "rule " + id + ": unit is required");
    Objects.requireNonNull(sustain, "rule " + id + ": sustain is required");
    Objects.requireNonNull(recovery, "rule " + id + ": recovery is required");
    Objects.requireNonNull(severityPolicy, "rule " + id + ": severity is required");
    if (!Double.isFinite(threshold)) {
      throw new IllegalArgumentException("rule " + id + ": threshold must be a finite number");
    }
    if (sustain.isNegative() || recovery.isNegative()) {
      throw new IllegalArgumentException("rule " + id + ": windows must not be negative");
    }
    if (severityPolicy.fixed() == null && threshold <= 0) {
      throw new IllegalArgumentException(
          "rule " + id + ": ratio-based severity needs a positive threshold");
    }
  }

  /** Unique per monitored service: the same rule type runs once for every service. */
  public String key() {
    return serviceName + "/" + id;
  }

  public boolean isBreaching(double value) {
    return comparison.isBreaching(value, threshold);
  }

  public Severity severityFor(double value) {
    return severityPolicy.classify(value, threshold, comparison);
  }

  /** e.g. "p95 1.432 s ≥ 1.000 s". */
  public String describe(double value) {
    return unit.format(value) + " " + comparison.symbol() + " " + unit.format(threshold);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("rule " + field + " must not be blank");
    }
  }
}
