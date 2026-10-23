package edu.cloudpulse.api.monitoring;

/** Health of a monitored service as reported by the API (FR-06, UC-01). */
public enum ServiceHealth {
  /** The detector has not completed a cycle yet. */
  STARTING,
  HEALTHY,
  /** At least one latency/error incident is active. */
  DEGRADED,
  /** An availability incident is active: Prometheus cannot scrape the service. */
  UNAVAILABLE,
  /** Telemetry is missing, so health cannot be evaluated (never reported as healthy). */
  UNKNOWN
}
