package edu.cloudpulse.api.telemetry;

/** Outcome of one telemetry query. Only {@link #OK} carries a measurement. */
public enum TelemetryStatus {
  /** Prometheus answered with a finite value. */
  OK,
  /** Prometheus answered, but there is nothing to measure (empty result, NaN, no traffic). */
  NO_DATA,
  /** Prometheus could not be reached or returned an error. */
  UNAVAILABLE
}
