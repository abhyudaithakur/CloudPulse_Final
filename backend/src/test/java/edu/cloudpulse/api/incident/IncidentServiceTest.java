package edu.cloudpulse.api.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.Severity;
import edu.cloudpulse.api.support.TestRules;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Incident lifecycle rules with the repositories mocked; no database needed. */
class IncidentServiceTest {

  private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

  private final IncidentRepository incidents = mock(IncidentRepository.class);
  private final EvidenceRepository evidence = mock(EvidenceRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AtomicReference<Incident> stored = new AtomicReference<>();
  private final RuleDefinition rule = TestRules.latency();
  private IncidentService service;

  @BeforeEach
  void setUp() {
    CloudPulseProperties properties =
        new CloudPulseProperties(
            null,
            new CloudPulseProperties.Detection(Duration.ofSeconds(20), Duration.ofSeconds(30), null),
            null,
            null,
            null,
            null);
    service = new IncidentService(incidents, evidence, audit, meters, properties);
    when(incidents.findFirstByRuleIdAndServiceNameAndStatus(any(), any(), eq(IncidentStatus.ACTIVE)))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()).filter(Incident::isActive));
    when(incidents.save(any(Incident.class)))
        .thenAnswer(
            invocation -> {
              Incident incident = invocation.getArgument(0);
              ReflectionTestUtils.setField(incident, "id", 7L);
              stored.set(incident);
              return incident;
            });
  }

  private List<EvidenceKind> recordedEvidenceKinds(int expected) {
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidence, times(expected)).save(captor.capture());
    return captor.getAllValues().stream().map(Evidence::getKind).toList();
  }

  @Test
  @DisplayName("FR-03/FR-05/FR-13: opening stores severity, evidence and an audit event")
  void fr03_fr05_fr13_openRecordsIncidentEvidenceAndAudit() {
    Incident incident = service.open(rule, T0, T0.plusSeconds(32), 1.6);

    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACTIVE);
    assertThat(incident.getSeverity()).isEqualTo(Severity.HIGH);
    assertThat(incident.key()).isEqualTo("CP-0007");
    assertThat(incident.getServiceName()).isEqualTo("checkout-api");
    assertThat(incident.getBreachStartedAt()).isEqualTo(T0);
    assertThat(incident.getOpenedAt()).isEqualTo(T0.plusSeconds(32));
    assertThat(incident.detectionDelaySeconds()).isEqualTo(2.0);

    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidence).save(captor.capture());
    Evidence first = captor.getValue();
    assertThat(first.getKind()).isEqualTo(EvidenceKind.BREACH_SUSTAINED);
    assertThat(first.getMetric()).isEqualTo("LATENCY_P95_SUSTAINED");
    assertThat(first.getObservedValue()).isEqualTo(1.6);
    assertThat(first.getThresholdValue()).isEqualTo(1.0);
    assertThat(first.getSource()).isEqualTo("prometheus");
    assertThat(first.getPromql()).isEqualTo(rule.query());
    assertThat(first.getObservedAt()).isEqualTo(T0.plusSeconds(32));

    verify(audit)
        .record(
            eq(AuditAction.INCIDENT_OPENED),
            eq(AuditService.ACTOR_DETECTOR),
            eq("incident:CP-0007"),
            eq(7L),
            eq(AuditResult.SUCCESS),
            contains("HIGH"));
    assertThat(meters.get("cloudpulse.incidents.opened").tag("severity", "HIGH").counter().count())
        .isEqualTo(1.0);
    assertThat(meters.get("cloudpulse.detection.delay").summary().totalAmount()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("FR-04: opening is idempotent while an incident is already active")
  void fr04_openIsIdempotent() {
    service.open(rule, T0, T0.plusSeconds(30), 1.2);
    service.open(rule, T0, T0.plusSeconds(35), 1.3);

    verify(incidents, times(1)).save(any(Incident.class));
    assertThat(stored.get().getLastValue()).isEqualTo(1.3);
  }

  @Test
  @DisplayName("FR-03/FR-13: severity escalates on a worse sample, is audited, and never auto-downgrades")
  void fr03_fr13_severityEscalatesOnly() {
    Incident incident = service.open(rule, T0, T0.plusSeconds(30), 1.2);
    assertThat(incident.getSeverity()).isEqualTo(Severity.MEDIUM);

    service.recordBreach(rule, T0.plusSeconds(35), 2.4);
    assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(incident.getPeakValue()).isEqualTo(2.4);
    verify(audit)
        .record(
            eq(AuditAction.SEVERITY_ESCALATED),
            eq(AuditService.ACTOR_DETECTOR),
            eq("incident:CP-0007"),
            eq(7L),
            eq(AuditResult.SUCCESS),
            contains("MEDIUM → CRITICAL"));

    service.recordBreach(rule, T0.plusSeconds(40), 1.1);
    assertThat(incident.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(incident.getLastValue()).isEqualTo(1.1);
    assertThat(incident.getPeakValue()).isEqualTo(2.4);
    assertThat(recordedEvidenceKinds(2))
        .containsExactly(EvidenceKind.BREACH_SUSTAINED, EvidenceKind.SEVERITY_ESCALATED);
  }

  @Test
  @DisplayName("FR-05: ongoing breaches add a sample at most once per evidence interval")
  void fr05_periodicSamples() {
    service.open(rule, T0, T0.plusSeconds(30), 1.2);
    service.recordBreach(rule, T0.plusSeconds(45), 1.2);
    service.recordBreach(rule, T0.plusSeconds(60), 1.2);
    service.recordBreach(rule, T0.plusSeconds(65), 1.2);

    assertThat(recordedEvidenceKinds(2))
        .containsExactly(EvidenceKind.BREACH_SUSTAINED, EvidenceKind.SAMPLE);
  }

  @Test
  @DisplayName("UC-05/FR-13: resolving records recovery evidence and an audit event, once")
  void uc05_fr13_resolveRecordsEvidenceAndAudit() {
    Incident incident = service.open(rule, T0, T0.plusSeconds(30), 1.4);

    service.resolve(rule, T0.plusSeconds(120), 0.04, T0.plusSeconds(110));
    service.resolve(rule, T0.plusSeconds(125), 0.04, T0.plusSeconds(110));

    assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    assertThat(incident.getResolvedAt()).isEqualTo(T0.plusSeconds(120));
    assertThat(recordedEvidenceKinds(2))
        .containsExactly(EvidenceKind.BREACH_SUSTAINED, EvidenceKind.RECOVERY_CONFIRMED);
    verify(audit, times(1))
        .record(
            eq(AuditAction.INCIDENT_RESOLVED),
            eq(AuditService.ACTOR_DETECTOR),
            eq("incident:CP-0007"),
            eq(7L),
            eq(AuditResult.SUCCESS),
            contains("clear for 10 s"));
  }
}
