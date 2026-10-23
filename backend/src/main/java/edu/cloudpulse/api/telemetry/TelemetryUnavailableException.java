package edu.cloudpulse.api.telemetry;

/** Raised when a telemetry read that has no "no data" representation cannot be completed. */
public class TelemetryUnavailableException extends RuntimeException {

  public TelemetryUnavailableException(String message) {
    super(message);
  }
}
