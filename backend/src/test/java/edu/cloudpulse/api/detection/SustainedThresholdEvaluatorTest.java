package edu.cloudpulse.api.detection;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cloudpulse.api.detection.Evaluation.Action;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.support.TestRules;
import edu.cloudpulse.api.telemetry.TelemetryReading;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The detection state machine, driven with synthetic time (no sleeping). Test names start with
 * the RAD requirement they verify; CI turns them into the traceability report.
 */
class SustainedThresholdEvaluatorTest {

  private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

  private final RuleDefinition latency = TestRules.latency();
  private SustainedThresholdEvaluator evaluator;
  private RuleWindow window;

  @BeforeEach
  void setUp() {
    evaluator = new SustainedThresholdEvaluator(Duration.ofSeconds(20));
    window = new RuleWindow();
  }

  private Evaluation sample(int secondsAfterT0, double value, boolean incidentActive) {
    return evaluator.evaluate(
        latency, window, TelemetryReading.ok(value), incidentActive, T0.plusSeconds(secondsAfterT0));
  }

  @Test
  @DisplayName("FR-03: a violation shorter than the sustain window opens nothing")
  void fr03_noIncidentBeforeSustainWindow() {
    assertThat(sample(0, 1.4, false).action()).isEqualTo(Action.NONE);
    assertThat(sample(15, 1.4, false).phase()).isEqualTo(RulePhase.PENDING);
    Evaluation last = sample(25, 1.4, false);

    assertThat(last.action()).isEqualTo(Action.NONE);
    assertThat(last.breachStartedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("FR-03: the incident opens once the violation has held for the full window")
  void fr03_opensExactlyWhenSustainWindowIsSatisfied() {
    sample(0, 1.4, false);
    sample(15, 1.4, false);
    Evaluation atBoundary = sample(30, 1.4, false);

    assertThat(atBoundary.action()).isEqualTo(Action.OPEN);
    assertThat(atBoundary.phase()).isEqualTo(RulePhase.BREACHING);
    assertThat(atBoundary.breachStartedAt()).isEqualTo(T0);
    assertThat(atBoundary.value()).isEqualTo(1.4);
  }

  @Test
  @DisplayName("FR-03: a dip below threshold restarts the sustain window")
  void fr03_dipBelowThresholdRestartsSustainWindow() {
    sample(0, 1.4, false);
    sample(10, 1.4, false);
    sample(20, 0.3, false);
    sample(25, 1.4, false);
    sample(35, 1.4, false);
    sample(45, 1.4, false);

    Evaluation stillPending = sample(50, 1.4, false);
    assertThat(stillPending.action()).isEqualTo(Action.NONE);
    assertThat(stillPending.breachStartedAt()).isEqualTo(T0.plusSeconds(25));
    assertThat(sample(55, 1.4, false).action()).isEqualTo(Action.OPEN);
  }

  @Test
  @DisplayName("FR-04: while an incident is active, violations update it instead of opening another")
  void fr04_activeIncidentIsUpdatedNotDuplicated() {
    sample(0, 1.4, false);
    sample(10, 1.4, false);
    sample(20, 1.4, false);
    assertThat(sample(30, 1.4, false).action()).isEqualTo(Action.OPEN);

    assertThat(sample(35, 1.6, true).action()).isEqualTo(Action.UPDATE);
    assertThat(sample(40, 2.4, true).action()).isEqualTo(Action.UPDATE);
    assertThat(sample(45, 2.4, true).breachStartedAt()).isEqualTo(T0);
  }

  @Test
  @DisplayName("UC-05: resolution waits for the full recovery window")
  void uc05_resolvesOnlyAfterRecoveryWindow() {
    Evaluation first = sample(100, 0.04, true);
    assertThat(first.phase()).isEqualTo(RulePhase.RECOVERING);
    assertThat(first.recoveryStartedAt()).isEqualTo(T0.plusSeconds(100));
    assertThat(sample(105, 0.04, true).action()).isEqualTo(Action.NONE);

    Evaluation done = sample(110, 0.04, true);
    assertThat(done.action()).isEqualTo(Action.RESOLVE);
    assertThat(done.recoveryStartedAt()).isEqualTo(T0.plusSeconds(100));
  }

  @Test
  @DisplayName("UC-05: a violation during recovery restarts the recovery window (no flapping)")
  void uc05_violationDuringRecoveryRestartsRecoveryWindow() {
    sample(100, 0.04, true);
    sample(105, 0.04, true);
    assertThat(sample(108, 1.3, true).action()).isEqualTo(Action.UPDATE);

    assertThat(sample(115, 0.04, true).action()).isEqualTo(Action.NONE);
    assertThat(sample(125, 0.04, true).action()).isEqualTo(Action.RESOLVE);
  }

  @Test
  @DisplayName("UC-05: a failed resolve is retried on the next cycle without waiting again")
  void uc05_failedResolveIsRetriedImmediately() {
    sample(100, 0.04, true);
    assertThat(sample(110, 0.04, true).action()).isEqualTo(Action.RESOLVE);
    // the incident is still active because the resolve did not commit
    assertThat(sample(115, 0.04, true).action()).isEqualTo(Action.RESOLVE);
  }

  @Test
  @DisplayName("NFR-04: unavailable telemetry never resolves an active incident")
  void nfr04_unavailableTelemetryNeverResolves() {
    sample(100, 0.04, true);
    for (int t = 105; t <= 300; t += 5) {
      Evaluation e =
          evaluator.evaluate(
              latency,
              window,
              TelemetryReading.unavailable("connection refused"),
              true,
              T0.plusSeconds(t));
      assertThat(e.action()).isEqualTo(Action.NONE);
      assertThat(e.phase()).isEqualTo(RulePhase.UNAVAILABLE);
      assertThat(e.value()).isNull();
    }
  }

  @Test
  @DisplayName("NFR-04: 'no data' is not healthy and is not reused as a measurement")
  void nfr04_noDataIsNotTreatedAsHealthy() {
    sample(100, 0.04, true);
    Evaluation e =
        evaluator.evaluate(
            latency, window, TelemetryReading.noData("NaN"), true, T0.plusSeconds(105));

    assertThat(e.phase()).isEqualTo(RulePhase.NO_DATA);
    assertThat(e.action()).isEqualTo(Action.NONE);
    assertThat(e.value()).isNull();
    assertThat(window.recoveryStartedAt()).isNull();
  }

  @Test
  @DisplayName("NFR-04: a telemetry gap breaks the sustain window instead of bridging it")
  void nfr04_telemetryGapResetsSustainWindow() {
    sample(0, 1.4, false);
    sample(10, 1.4, false);
    evaluator.evaluate(
        latency, window, TelemetryReading.unavailable("timeout"), false, T0.plusSeconds(15));

    Evaluation afterGap = sample(35, 1.4, false);
    assertThat(afterGap.action()).isEqualTo(Action.NONE);
    assertThat(afterGap.breachStartedAt()).isEqualTo(T0.plusSeconds(35));
  }

  @Test
  @DisplayName("NFR-04: usable samples too far apart do not count as a continuous violation")
  void nfr04_samplesFurtherApartThanMaxGapResetWindow() {
    sample(0, 1.4, false);
    Evaluation late = sample(45, 1.4, false);

    assertThat(late.action()).isEqualTo(Action.NONE);
    assertThat(late.breachStartedAt()).isEqualTo(T0.plusSeconds(45));
  }

  @Test
  @DisplayName("FR-02: a BELOW rule (availability, up < 1) breaches on 0 and recovers on 1")
  void fr02_belowComparisonForAvailability() {
    RuleDefinition availability = TestRules.availability();
    RuleWindow w = new RuleWindow();

    evaluator.evaluate(availability, w, TelemetryReading.ok(0), false, T0);
    Evaluation open = evaluator.evaluate(availability, w, TelemetryReading.ok(0), false, T0.plusSeconds(15));
    assertThat(open.action()).isEqualTo(Action.OPEN);

    evaluator.evaluate(availability, w, TelemetryReading.ok(1), true, T0.plusSeconds(20));
    Evaluation resolve =
        evaluator.evaluate(availability, w, TelemetryReading.ok(1), true, T0.plusSeconds(30));
    assertThat(resolve.action()).isEqualTo(Action.RESOLVE);
  }

  @Test
  @DisplayName("FR-02: the error-rate rule opens on a sustained 5xx ratio above 5 %")
  void fr02_errorRateRuleOpensOnSustainedErrors() {
    RuleDefinition errors = TestRules.errorRate();
    RuleWindow w = new RuleWindow();

    assertThat(evaluator.evaluate(errors, w, TelemetryReading.ok(0.02), false, T0).phase())
        .isEqualTo(RulePhase.HEALTHY);
    for (int t = 5; t <= 25; t += 10) {
      evaluator.evaluate(errors, w, TelemetryReading.ok(0.30), false, T0.plusSeconds(t));
    }
    Evaluation open =
        evaluator.evaluate(errors, w, TelemetryReading.ok(0.30), false, T0.plusSeconds(35));
    assertThat(open.action()).isEqualTo(Action.OPEN);
  }

  @Test
  @DisplayName("FR-02: healthy samples with no incident report HEALTHY and keep no window")
  void fr02_healthySamplesKeepNoWindow() {
    Evaluation e = sample(0, 0.05, false);

    assertThat(e.phase()).isEqualTo(RulePhase.HEALTHY);
    assertThat(e.action()).isEqualTo(Action.NONE);
    assertThat(window.breachStartedAt()).isNull();
    assertThat(window.recoveryStartedAt()).isNull();
  }
}
