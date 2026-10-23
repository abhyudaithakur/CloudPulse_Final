package edu.cloudpulse.api.telemetry;

import java.util.Objects;

/**
 * Result of evaluating one rule query. A reading without a usable value is never converted to a
 * number: NO_DATA and UNAVAILABLE are not "healthy" and are not reused as current evidence
 * (NFR-04).
 */
public record TelemetryReading(TelemetryStatus status, Double value, String detail) {

  public TelemetryReading {
    Objects.requireNonNull(status, "status");
    if (status == TelemetryStatus.OK && (value == null || !Double.isFinite(value))) {
      throw new IllegalArgumentException("an OK reading needs a finite value");
    }
    if (status != TelemetryStatus.OK && value != null) {
      throw new IllegalArgumentException("only OK readings carry a value");
    }
    detail = detail == null ? "" : detail;
  }

  public static TelemetryReading ok(double value) {
    return new TelemetryReading(TelemetryStatus.OK, value, "");
  }

  public static TelemetryReading noData(String detail) {
    return new TelemetryReading(TelemetryStatus.NO_DATA, null, detail);
  }

  public static TelemetryReading unavailable(String detail) {
    return new TelemetryReading(TelemetryStatus.UNAVAILABLE, null, detail);
  }

  public boolean isUsable() {
    return status == TelemetryStatus.OK;
  }
}
