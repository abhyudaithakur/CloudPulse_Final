package edu.cloudpulse.api.domain;

/**
 * Deterministic mapping from an observed value to a severity.
 *
 * <p>Either a fixed severity (for example availability is always CRITICAL), or bands expressed as
 * how far the value is past the threshold: for an ABOVE rule the ratio is {@code value /
 * threshold}, for a BELOW rule it is {@code threshold / value}. A ratio at or past {@code
 * criticalRatio} is CRITICAL, at or past {@code highRatio} is HIGH, anything else that breaches is
 * MEDIUM.
 */
public record SeverityPolicy(Severity fixed, double highRatio, double criticalRatio) {

  public SeverityPolicy {
    if (fixed == null) {
      if (!(highRatio >= 1.0) || !(criticalRatio >= highRatio)) {
        throw new IllegalArgumentException(
            "severity bands need 1.0 <= highRatio <= criticalRatio, got high="
                + highRatio
                + " critical="
                + criticalRatio);
      }
    }
  }

  public static SeverityPolicy fixed(Severity severity) {
    return new SeverityPolicy(severity, 0, 0);
  }

  public static SeverityPolicy bands(double highRatio, double criticalRatio) {
    return new SeverityPolicy(null, highRatio, criticalRatio);
  }

  public Severity classify(double value, double threshold, Comparison comparison) {
    if (fixed != null) {
      return fixed;
    }
    double ratio;
    if (comparison == Comparison.ABOVE) {
      ratio = value / threshold;
    } else {
      ratio = value <= 0 ? Double.POSITIVE_INFINITY : threshold / value;
    }
    if (ratio >= criticalRatio) {
      return Severity.CRITICAL;
    }
    if (ratio >= highRatio) {
      return Severity.HIGH;
    }
    return Severity.MEDIUM;
  }
}
