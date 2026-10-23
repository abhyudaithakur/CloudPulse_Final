package edu.cloudpulse.api.detection;

import java.time.Instant;

/**
 * Outcome of evaluating one rule once.
 *
 * @param phase rule phase after this sample
 * @param action what the incident module must do
 * @param value the usable measurement, or null when telemetry had none
 * @param breachStartedAt start of the current continuous violation, if any
 * @param recoveryStartedAt start of the current continuous recovery, if any
 */
public record Evaluation(
    RulePhase phase, Action action, Double value, Instant breachStartedAt, Instant recoveryStartedAt) {

  public enum Action {
    NONE,
    /** Sustain window satisfied and no active incident: open one (FR-03). */
    OPEN,
    /** Still violating with an active incident: add evidence to it, never a new one (FR-04). */
    UPDATE,
    /** Recovery window satisfied: resolve the active incident (UC-05). */
    RESOLVE
  }
}
