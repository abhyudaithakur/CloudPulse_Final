package edu.cloudpulse.api.detection;

import java.time.Instant;

/**
 * Health of the telemetry pipeline as seen by the detector (UC-01 alternative flow, NFR-04).
 *
 * @param status STARTING before the first cycle, then CONNECTED, DEGRADED (some rule queries
 *     failing) or UNAVAILABLE (every rule query failing)
 * @param lastAttemptAt when the latest evaluation cycle ran
 * @param lastSuccessAt when Prometheus last answered any rule query
 * @param lastError the most recent query failure, empty when CONNECTED
 */
public record PipelineSnapshot(
    Status status,
    String source,
    Instant lastAttemptAt,
    Instant lastSuccessAt,
    String lastError,
    int failingQueries,
    int totalQueries,
    long evaluationIntervalMs) {

  public enum Status {
    STARTING,
    CONNECTED,
    DEGRADED,
    UNAVAILABLE
  }

  static PipelineSnapshot starting(int totalQueries, long evaluationIntervalMs) {
    return new PipelineSnapshot(
        Status.STARTING, "prometheus", null, null, "", 0, totalQueries, evaluationIntervalMs);
  }
}
