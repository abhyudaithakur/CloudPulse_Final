package edu.cloudpulse.api.domain;

/** Incident severity, ordered from least to most severe. */
public enum Severity {
  MEDIUM,
  HIGH,
  CRITICAL;

  public boolean isHigherThan(Severity other) {
    return other == null || compareTo(other) > 0;
  }
}
