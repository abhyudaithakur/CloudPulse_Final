package edu.cloudpulse.api.detection;

/** Where a rule currently is in its detection lifecycle. */
public enum RulePhase {
  /** Not evaluated yet since the API started. Never produced by the evaluator. */
  STARTING,
  /** Usable sample, not violating, no active incident. */
  HEALTHY,
  /** Violating, but the sustain window is not yet satisfied; no incident yet. */
  PENDING,
  /** Violating with an active incident (just opened or ongoing). */
  BREACHING,
  /** Back within threshold with an active incident; the recovery window is running. */
  RECOVERING,
  /** Prometheus answered but had nothing to measure. Health cannot be evaluated. */
  NO_DATA,
  /** Prometheus could not be queried. Health cannot be evaluated. */
  UNAVAILABLE;

  public boolean isEvaluable() {
    return this != STARTING && this != NO_DATA && this != UNAVAILABLE;
  }
}
