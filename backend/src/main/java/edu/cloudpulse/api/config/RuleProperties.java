package edu.cloudpulse.api.config;

import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.Severity;
import edu.cloudpulse.api.domain.SeverityPolicy;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.convert.DurationUnit;

/**
 * One entry of {@code cloudpulse.rule-templates}. A template is expanded once per monitored
 * service: {@code {service}} in the query becomes the service name, so adding a service to the
 * configuration gives it every rule (NFR-10). Plain numbers for {@code sustain}/{@code recovery}
 * are seconds, so the Compose file can keep its {@code DETECTION_SUSTAIN_SECONDS=30} variables.
 */
public record RuleProperties(
    String id,
    String title,
    String query,
    Comparison comparison,
    Double threshold,
    MetricUnit unit,
    @DurationUnit(ChronoUnit.SECONDS) Duration sustain,
    @DurationUnit(ChronoUnit.SECONDS) Duration recovery,
    SeverityProperties severity) {

  public static final String SERVICE_PLACEHOLDER = "{service}";

  public record SeverityProperties(Severity fixed, Double highRatio, Double criticalRatio) {}

  /** The rule for one service: the template with {@code {service}} filled in. */
  public RuleDefinition toDefinition(String serviceName) {
    if (threshold == null) {
      throw new IllegalArgumentException("rule " + id + ": threshold is required");
    }
    if (query == null || !query.contains(SERVICE_PLACEHOLDER)) {
      throw new IllegalArgumentException(
          "rule " + id + ": query must contain " + SERVICE_PLACEHOLDER + " so it applies per service");
    }
    SeverityPolicy policy;
    if (severity == null) {
      policy = SeverityPolicy.bands(1.5, 2.0);
    } else if (severity.fixed() != null) {
      policy = SeverityPolicy.fixed(severity.fixed());
    } else {
      policy =
          SeverityPolicy.bands(
              severity.highRatio() == null ? 1.5 : severity.highRatio(),
              severity.criticalRatio() == null ? 2.0 : severity.criticalRatio());
    }
    return new RuleDefinition(
        id,
        serviceName,
        title,
        query.replace(SERVICE_PLACEHOLDER, serviceName),
        comparison == null ? Comparison.ABOVE : comparison,
        threshold,
        unit,
        sustain,
        recovery,
        policy);
  }
}
