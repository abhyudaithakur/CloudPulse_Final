package edu.cloudpulse.api.detection;

import edu.cloudpulse.api.detection.Evaluation.Action;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.telemetry.TelemetryReading;
import edu.cloudpulse.api.telemetry.TelemetryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Detection module core: a pure state machine with no Spring, database or clock dependencies, so
 * every requirement it implements can be unit-tested with synthetic time.
 *
 * <ul>
 *   <li>FR-03: an incident opens only after the violation holds for the whole sustain window.
 *   <li>FR-04: while an incident is active, further violations update it ({@code UPDATE}).
 *   <li>UC-05: an incident resolves only after the value stays healthy for the recovery window;
 *       any violation during recovery restarts that window (no flapping).
 *   <li>NFR-04: a sample with no usable value resets both windows and never resolves anything.
 *       Gaps longer than {@code maxSampleGap} between usable samples also reset the windows,
 *       because the rule has no evidence the condition held during the gap.
 * </ul>
 */
public final class SustainedThresholdEvaluator {

  private final Duration maxSampleGap;

  public SustainedThresholdEvaluator(Duration maxSampleGap) {
    this.maxSampleGap = Objects.requireNonNull(maxSampleGap, "maxSampleGap");
  }

  public Evaluation evaluate(
      RuleDefinition rule,
      RuleWindow window,
      TelemetryReading reading,
      boolean incidentActive,
      Instant now) {

    if (!reading.isUsable()) {
      window.reset();
      RulePhase phase =
          reading.status() == TelemetryStatus.NO_DATA ? RulePhase.NO_DATA : RulePhase.UNAVAILABLE;
      return new Evaluation(phase, Action.NONE, null, null, null);
    }

    double value = reading.value();
    Instant previous = window.lastSampleAt();
    if (previous != null && Duration.between(previous, now).compareTo(maxSampleGap) > 0) {
      window.reset();
    }
    window.markSample(now);

    if (rule.isBreaching(value)) {
      window.clearRecovery();
      Instant since = window.startBreachIfAbsent(now);
      if (incidentActive) {
        return new Evaluation(RulePhase.BREACHING, Action.UPDATE, value, since, null);
      }
      if (elapsed(since, now).compareTo(rule.sustain()) >= 0) {
        return new Evaluation(RulePhase.BREACHING, Action.OPEN, value, since, null);
      }
      return new Evaluation(RulePhase.PENDING, Action.NONE, value, since, null);
    }

    window.clearBreach();
    if (!incidentActive) {
      window.clearRecovery();
      return new Evaluation(RulePhase.HEALTHY, Action.NONE, value, null, null);
    }
    Instant recoverySince = window.startRecoveryIfAbsent(now);
    if (elapsed(recoverySince, now).compareTo(rule.recovery()) >= 0) {
      // The recovery window is cleared on the next healthy sample once the incident is gone, so
      // a failed resolve (e.g. a database error) is retried immediately instead of waiting again.
      return new Evaluation(RulePhase.HEALTHY, Action.RESOLVE, value, null, recoverySince);
    }
    return new Evaluation(RulePhase.RECOVERING, Action.NONE, value, null, recoverySince);
  }

  private static Duration elapsed(Instant since, Instant now) {
    return Duration.between(since, now);
  }
}
