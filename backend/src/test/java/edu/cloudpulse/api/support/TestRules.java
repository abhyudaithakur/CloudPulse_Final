package edu.cloudpulse.api.support;

import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.Severity;
import edu.cloudpulse.api.domain.SeverityPolicy;
import java.time.Duration;

/** Rule fixtures matching the defaults in application.yml. */
public final class TestRules {

  public static final String LATENCY_QUERY =
      "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{application=\"checkout-api\",uri=\"/api/work\"}[1m])))";

  private TestRules() {}

  /** p95 ≥ 1.0 s for 30 s, recover after 10 s, HIGH at 1.5×, CRITICAL at 2×. */
  public static RuleDefinition latency() {
    return latency("checkout-api");
  }

  public static RuleDefinition latency(String service) {
    return new RuleDefinition(
        "LATENCY_P95_SUSTAINED",
        service,
        "Sustained high request latency",
        LATENCY_QUERY.replace("checkout-api", service),
        Comparison.ABOVE,
        1.0,
        MetricUnit.SECONDS,
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        SeverityPolicy.bands(1.5, 2.0));
  }

  /** 5xx ratio ≥ 5 % for 30 s. */
  public static RuleDefinition errorRate() {
    return errorRate("checkout-api");
  }

  public static RuleDefinition errorRate(String service) {
    return new RuleDefinition(
        "ERROR_RATE_SUSTAINED",
        service,
        "Sustained HTTP 5xx error rate",
        "sum(rate(errors{application=\"" + service + "\"}[1m])) / sum(rate(requests[1m]))",
        Comparison.ABOVE,
        0.05,
        MetricUnit.RATIO,
        Duration.ofSeconds(30),
        Duration.ofSeconds(10),
        SeverityPolicy.bands(2.0, 4.0));
  }

  /** up &lt; 1 for 15 s. */
  public static RuleDefinition availability() {
    return availability("checkout-api");
  }

  public static RuleDefinition availability(String service) {
    return new RuleDefinition(
        "TARGET_AVAILABILITY",
        service,
        "Service unreachable by Prometheus",
        "min(up{job=\"" + service + "\"})",
        Comparison.BELOW,
        1,
        MetricUnit.BOOLEAN,
        Duration.ofSeconds(15),
        Duration.ofSeconds(10),
        SeverityPolicy.fixed(Severity.CRITICAL));
  }
}
