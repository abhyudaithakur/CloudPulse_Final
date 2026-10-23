package edu.cloudpulse.api.detection;

import java.time.Instant;

/**
 * Mutable per-rule window state. Owned by the single detection thread; the API reads immutable
 * snapshots instead of this object.
 */
public final class RuleWindow {

  private Instant breachStartedAt;
  private Instant recoveryStartedAt;
  private Instant lastSampleAt;

  public Instant breachStartedAt() {
    return breachStartedAt;
  }

  public Instant recoveryStartedAt() {
    return recoveryStartedAt;
  }

  public Instant lastSampleAt() {
    return lastSampleAt;
  }

  Instant startBreachIfAbsent(Instant now) {
    if (breachStartedAt == null) {
      breachStartedAt = now;
    }
    return breachStartedAt;
  }

  Instant startRecoveryIfAbsent(Instant now) {
    if (recoveryStartedAt == null) {
      recoveryStartedAt = now;
    }
    return recoveryStartedAt;
  }

  void clearBreach() {
    breachStartedAt = null;
  }

  void clearRecovery() {
    recoveryStartedAt = null;
  }

  void markSample(Instant now) {
    lastSampleAt = now;
  }

  /** Forget everything: continuity of evidence was lost. */
  void reset() {
    breachStartedAt = null;
    recoveryStartedAt = null;
    lastSampleAt = null;
  }
}
