package edu.cloudpulse.api.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.incident.IncidentService;
import edu.cloudpulse.api.support.MutableClock;
import edu.cloudpulse.api.support.TestRules;
import edu.cloudpulse.api.telemetry.PrometheusClient;
import edu.cloudpulse.api.telemetry.TelemetryReading;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The detection cycle with telemetry and persistence mocked. */
class DetectionEngineTest {

  private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

  private final PrometheusClient prometheus = mock(PrometheusClient.class);
  private final IncidentService incidents = mock(IncidentService.class);
  private final AuditService audit = mock(AuditService.class);
  private final MutableClock clock = new MutableClock(T0);
  private final RuleDefinition latency = TestRules.latency();
  private DetectionEngine engine;

  @BeforeEach
  void setUp() {
    engine =
        new DetectionEngine(
            new RuleCatalog(List.of(latency)),
            prometheus,
            new SustainedThresholdEvaluator(Duration.ofSeconds(20)),
            incidents,
            audit,
            clock,
            new SimpleMeterRegistry(),
            new CloudPulseProperties(null, null, null, null, null, null));
  }

  private void cycles(int count) {
    for (int i = 0; i < count; i++) {
      engine.evaluateAll();
      clock.advance(Duration.ofSeconds(5));
    }
  }

  private void telemetry(TelemetryReading reading) {
    when(prometheus.instant(anyString(), any())).thenReturn(reading);
  }

  @Test
  @DisplayName("UC-01: before the first cycle every rule reports STARTING")
  void uc01_startingBeforeFirstCycle() {
    assertThat(engine.pipeline().status()).isEqualTo(PipelineSnapshot.Status.STARTING);
    assertThat(engine.rules().get(0).phase()).isEqualTo(RulePhase.STARTING);
  }

  @Test
  @DisplayName("FR-01/FR-03: each cycle queries the rule; the incident opens at the 30 s mark")
  void fr01_fr03_opensAfterSustainedViolation() {
    telemetry(TelemetryReading.ok(1.6));
    when(incidents.hasActive(latency)).thenReturn(false);

    cycles(7); // t = 0, 5, ..., 30

    verify(prometheus, times(7)).instant(eq(latency.query()), eq(latency.comparison()));
    verify(incidents, times(1)).open(eq(latency), eq(T0), eq(T0.plusSeconds(30)), eq(1.6));
    assertThat(engine.rules().get(0).phase()).isEqualTo(RulePhase.BREACHING);
    assertThat(engine.rules().get(0).value()).isEqualTo(1.6);
    assertThat(engine.pipeline().status()).isEqualTo(PipelineSnapshot.Status.CONNECTED);
  }

  @Test
  @DisplayName("NFR-04/FR-13: an outage is reported, audited once, never resolves, and recovery is audited")
  void nfr04_fr13_outageReportedAndAudited() {
    when(incidents.hasActive(latency)).thenReturn(true);
    telemetry(TelemetryReading.unavailable("ResourceAccessException: Connection refused"));

    cycles(4);

    assertThat(engine.pipeline().status()).isEqualTo(PipelineSnapshot.Status.UNAVAILABLE);
    assertThat(engine.pipeline().lastError()).contains("Connection refused");
    assertThat(engine.rules().get(0).value()).isNull();
    assertThat(engine.rules().get(0).phase()).isEqualTo(RulePhase.UNAVAILABLE);
    verify(audit, times(1))
        .record(
            eq(AuditAction.TELEMETRY_UNAVAILABLE),
            eq(AuditService.ACTOR_DETECTOR),
            eq("telemetry:prometheus"),
            isNull(),
            eq(AuditResult.FAILURE),
            contains("Connection refused"));
    verify(incidents, never()).resolve(any(), any(), anyDouble(), any());

    telemetry(TelemetryReading.ok(0.05));
    cycles(1);

    assertThat(engine.pipeline().status()).isEqualTo(PipelineSnapshot.Status.CONNECTED);
    verify(audit, times(1))
        .record(
            eq(AuditAction.TELEMETRY_RESTORED),
            eq(AuditService.ACTOR_DETECTOR),
            eq("telemetry:prometheus"),
            isNull(),
            eq(AuditResult.SUCCESS),
            any());
  }

  @Test
  @DisplayName("FR-04: a duplicate rejected by the database unique index does not break the cycle")
  void fr04_duplicateKeyIsAbsorbed() {
    telemetry(TelemetryReading.ok(1.6));
    when(incidents.hasActive(latency)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("ux_incidents_one_active_per_rule_service"))
        .when(incidents)
        .open(any(), any(), any(), anyDouble());

    cycles(8);

    assertThat(engine.rules().get(0).phase()).isEqualTo(RulePhase.BREACHING);
  }

  @Test
  @DisplayName("UC-05: the cycle asks for resolution after the recovery window")
  void uc05_requestsResolutionAfterRecoveryWindow() {
    telemetry(TelemetryReading.ok(0.04));
    when(incidents.hasActive(latency)).thenReturn(true);

    cycles(3); // t = 0, 5, 10

    verify(incidents, times(1)).resolve(eq(latency), eq(T0.plusSeconds(10)), eq(0.04), eq(T0));
  }
}
