package edu.cloudpulse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Integration test of the whole API in Docker Compose mode (remediation off): real PostgreSQL,
 * real HTTP and JSON, scripted Prometheus and demo services for checkout-api and payment-api.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiITSupport.TestClockConfig.class)
class CloudPulseApiIT extends ApiITSupport {

  // ---- tests -------------------------------------------------------------------------------

  @Test
  @DisplayName("FR-03/FR-05/FR-07/FR-13/NFR-01: a sustained breach opens one incident with full evidence over HTTP")
  void fr03_fr05_fr07_fr13_nfr01_incidentWithEvidenceAndTimeline() {
    long id = openLatencyIncident(1.6);

    Reply detail = get("/api/incidents/" + id);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.read("$.incident.key")).isEqualTo("CP-0001");
    assertThat(detail.read("$.incident.status")).isEqualTo("ACTIVE");
    assertThat(detail.read("$.incident.severity")).isEqualTo("HIGH");
    assertThat(detail.read("$.incident.serviceName")).isEqualTo("checkout-api");
    assertThat(detail.read("$.incident.ruleId")).isEqualTo("LATENCY_P95_SUSTAINED");
    assertThat(detail.number("$.incident.detectionDelaySeconds")).isLessThanOrEqualTo(30.0);
    assertThat((String) detail.read("$.incident.openedAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

    assertThat(detail.read("$.evidence[0].kind")).isEqualTo("BREACH_SUSTAINED");
    assertThat(detail.number("$.evidence[0].observedValue")).isEqualTo(1.6);
    assertThat(detail.number("$.evidence[0].threshold")).isEqualTo(1.0);
    assertThat(detail.read("$.evidence[0].source")).isEqualTo("prometheus");
    assertThat((String) detail.read("$.evidence[0].promql")).contains("histogram_quantile");
    assertThat(detail.read("$.evidence[0].observedAt")).isNotNull();

    assertThat(detail.read("$.timeline[0].action")).isEqualTo("INCIDENT_OPENED");
    assertThat(detail.read("$.timeline[0].result")).isEqualTo("SUCCESS");
    assertThat(detail.read("$.runbooks[0].id")).isEqualTo("RESTART_DEPLOYMENT");

    Reply dashboard = get("/api/dashboard");
    assertThat(dashboard.read("$.services[0].health")).isEqualTo("DEGRADED");
    assertThat(dashboard.number("$.counts.active")).isEqualTo(1.0);
    assertThat(dashboard.read("$.pipeline.status")).isEqualTo("CONNECTED");
  }

  @Test
  @DisplayName("FR-04: continued violation and escalation keep a single incident")
  void fr04_sustainedFaultStaysOneIncidentAndEscalates() {
    long id = openLatencyIncident(1.2);
    TELEMETRY.checkout.latency = 2.4;
    cycles(6);

    Reply active = get("/api/incidents?status=ACTIVE");
    assertThat((Integer) active.read("$.length()")).isEqualTo(1);
    assertThat(((Number) active.read("$[0].id")).longValue()).isEqualTo(id);
    assertThat(active.read("$[0].severity")).isEqualTo("CRITICAL");
    assertThat(active.number("$[0].peakValue")).isEqualTo(2.4);

    List<String> actions = get("/api/incidents/" + id + "/audit").json().read("$[*].action");
    assertThat(actions).containsExactly("INCIDENT_OPENED", "SEVERITY_ESCALATED");
  }

  @Test
  @DisplayName("FR-04: the database itself rejects a second ACTIVE incident for the same rule and service")
  void fr04_partialUniqueIndexRejectsDuplicateActive() {
    String insert =
        "INSERT INTO incidents (rule_id, service_name, title, status, severity, unit, comparison,"
            + " threshold_value, sustain_seconds, recovery_seconds, breach_started_at, opened_at,"
            + " resolved_at, last_value, last_observed_at, peak_value, last_evidence_at, summary)"
            + " VALUES ('R', 'svc', 't', ?, 'HIGH', 'SECONDS', 'ABOVE', 1, 30, 10, now(), now(),"
            + " ?, 1.5, now(), 1.5, now(), 's')";
    jdbc.update(insert, "ACTIVE", null);
    jdbc.update(insert, "RESOLVED", java.sql.Timestamp.from(Instant.now()));

    assertThatThrownBy(() -> jdbc.update(insert, "ACTIVE", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("UC-05/FR-12 baseline: the incident resolves after the recovery window and health returns")
  void uc05_resolvesAfterRecoveryWindow() {
    long id = openLatencyIncident(1.6);
    TELEMETRY.checkout.latency = 0.04;
    cycles(3); // healthy at +0, +5, +10 s → resolves

    Reply detail = get("/api/incidents/" + id);
    assertThat(detail.read("$.incident.status")).isEqualTo("RESOLVED");
    assertThat(detail.read("$.incident.resolvedAt")).isNotNull();
    List<String> kinds = detail.json().read("$.evidence[*].kind");
    assertThat(kinds).last().isEqualTo("RECOVERY_CONFIRMED");
    List<String> actions = detail.json().read("$.timeline[*].action");
    assertThat(actions).contains("INCIDENT_RESOLVED");

    cycles(1);
    assertThat(get("/api/dashboard").read("$.services[0].health")).isEqualTo("HEALTHY");
  }

  @Test
  @DisplayName("NFR-04/UC-01 alt: Prometheus down is reported as unavailable/unknown, never stale or healthy")
  void nfr04_outageIsReportedNotHidden() {
    long id = openLatencyIncident(1.6);
    TELEMETRY.prometheusDown = true;
    cycles(4);

    Reply dashboard = get("/api/dashboard");
    assertThat(dashboard.read("$.pipeline.status")).isEqualTo("UNAVAILABLE");
    assertThat((String) dashboard.read("$.pipeline.lastError")).contains("503");
    assertThat(dashboard.read("$.services[0].health")).isEqualTo("UNKNOWN");
    assertThat(dashboard.read("$.rules[0].value")).isNull();
    assertThat(dashboard.read("$.rules[0].phase")).isEqualTo("UNAVAILABLE");
    assertThat(get("/api/incidents/" + id).read("$.incident.status")).isEqualTo("ACTIVE");

    Reply series = get("/api/services/checkout-api/rules/LATENCY_P95_SUSTAINED/series?minutes=5");
    assertThat(series.status()).isEqualTo(200);
    assertThat(series.read("$.status")).isEqualTo("UNAVAILABLE");

    TELEMETRY.prometheusDown = false;
    cycles(1);
    assertThat(get("/api/dashboard").read("$.pipeline.status")).isEqualTo("CONNECTED");

    List<String> actions = get("/api/audit?limit=50").json().read("$[*].action");
    assertThat(actions.stream().filter("TELEMETRY_UNAVAILABLE"::equals).count()).isEqualTo(1);
    assertThat(actions.stream().filter("TELEMETRY_RESTORED"::equals).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("NFR-05: audit events cannot be updated or deleted")
  void nfr05_auditTrailIsAppendOnly() {
    post("/api/demo/fault", "{\"mode\":\"high-latency\"}");

    assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET details = 'tampered'"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("FR-02/FR-09: a sustained 5xx error rate opens a CRITICAL error-rate incident")
  void fr02_fr09_errorRateIncident() {
    TELEMETRY.checkout.errorRate = 0.30;
    cycles(7);

    Reply active = get("/api/incidents?status=active&rule=ERROR_RATE_SUSTAINED");
    assertThat((Integer) active.read("$.length()")).isEqualTo(1);
    assertThat(active.read("$[0].severity")).isEqualTo("CRITICAL");
    assertThat(active.read("$[0].unit")).isEqualTo("RATIO");
  }

  @Test
  @DisplayName("FR-02: a scrape target that is down opens an availability incident; service is UNAVAILABLE")
  void fr02_availabilityIncident() {
    TELEMETRY.checkout.up = 0.0;
    cycles(4); // sustain 15 s → opens on the 15 s sample

    Reply dashboard = get("/api/dashboard");
    assertThat(dashboard.read("$.services[0].health")).isEqualTo("UNAVAILABLE");
    assertThat(get("/api/incidents?status=active").read("$[0].ruleId"))
        .isEqualTo("TARGET_AVAILABILITY");
  }

  @Test
  @DisplayName("UC-06/FR-13: fault injection is validated, applied to the demo service, and audited")
  void uc06_fr13_faultInjection() {
    Reply applied = post("/api/demo/fault", "{\"mode\":\"error-storm\"}");
    assertThat(applied.status()).isEqualTo(200);
    assertThat(applied.read("$.mode")).isEqualTo("error-storm");
    assertThat(TELEMETRY.checkout.faultMode).isEqualTo("error-storm");

    Reply audit = get("/api/audit?limit=1");
    assertThat(audit.read("$[0].action")).isEqualTo("FAULT_INJECTED");
    assertThat(audit.read("$[0].actor")).isEqualTo("operator");

    Reply rejected = post("/api/demo/fault", "{\"mode\":\"meltdown\"}");
    assertThat(rejected.status()).isEqualTo(400);
    assertThat(rejected.contentType()).contains("application/problem+json");
  }

  @Test
  @DisplayName("FR-06: services, rules and series endpoints; unknown ids are RFC 9457 problems")
  void fr06_restContract() {
    cycles(1);
    Reply services = get("/api/services");
    assertThat(services.read("$[0].name")).isEqualTo("checkout-api");
    assertThat(services.read("$[0].health")).isEqualTo("HEALTHY");
    List<String> ruleIds = services.json().read("$[0].ruleIds");
    assertThat(ruleIds)
        .containsExactly("LATENCY_P95_SUSTAINED", "ERROR_RATE_SUSTAINED", "TARGET_AVAILABILITY");
    List<String> dependsOn = services.json().read("$[0].dependsOn");
    assertThat(dependsOn).containsExactly("payment-api");
    assertThat(services.read("$[1].name")).isEqualTo("payment-api");
    assertThat(services.read("$[1].health")).isEqualTo("HEALTHY");
    assertThat((Integer) get("/api/rules").read("$.length()")).isEqualTo(6);

    Reply series = get("/api/services/checkout-api/rules/LATENCY_P95_SUSTAINED/series?minutes=5");
    assertThat(series.read("$.status")).isEqualTo("OK");
    assertThat((Integer) series.read("$.points.length()")).isEqualTo(2);
    assertThat(series.read("$.points[1].v")).isNull();

    Reply missing = get("/api/incidents/999");
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.contentType()).contains("application/problem+json");
    assertThat(missing.number("$.status")).isEqualTo(404.0);

    assertThat(get("/api/incidents?status=bogus").status()).isEqualTo(400);
    assertThat(get("/api/services/checkout-api/rules/NOPE/series").status()).isEqualTo(404);
  }

  @Test
  @DisplayName("NFR-07: dashboard and incident views answer well under 2 s")
  void nfr07_responseTimes() {
    openLatencyIncident(1.6);
    for (String path : List.of("/api/dashboard", "/api/incidents", "/api/incidents/1", "/api/services")) {
      for (int i = 0; i < 10; i++) {
        long started = System.nanoTime();
        assertThat(get(path).status()).isEqualTo(200);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs).as(path).isLessThan(2_000);
      }
    }
  }

  // ---- two services: probable source, stuck fault, remediation off ---------------------------

  @Test
  @DisplayName("FR-08/UC-03: a payment failure that breaks checkout names payment-api as the probable source")
  void fr08_uc03_dependencyIsProbableSource() {
    TELEMETRY.payment.errorRate = 0.6;
    cycles(2); // payment's breach begins 5 s before checkout's
    TELEMETRY.checkout.errorRate = 0.6;
    cycles(7);

    Reply active = get("/api/incidents?status=active");
    assertThat((Integer) active.read("$.length()")).isEqualTo(2);
    Reply checkoutList = get("/api/incidents?status=active&service=checkout-api");
    long checkoutId = checkoutList.id("$[0].id");
    assertThat(checkoutList.read("$[0].probableSource")).isEqualTo("payment-api");
    assertThat(checkoutList.read("$[0].correlation")).isEqualTo("DEPENDENCY");

    Reply detail = get("/api/incidents/" + checkoutId);
    assertThat(detail.read("$.probableSource.verdict")).isEqualTo("DEPENDENCY");
    assertThat(detail.read("$.probableSource.service")).isEqualTo("payment-api");
    assertThat((String) detail.read("$.probableSource.statement"))
        .startsWith("Probable source: payment-api.");
    assertThat(detail.read("$.probableSource.related[0].serviceName")).isEqualTo("payment-api");
    assertThat(detail.read("$.probableSource.related[0].relation")).isEqualTo("dependency");
    // The restart runbook recommends the probable source first, then the incident's own service.
    assertThat(detail.read("$.runbooks[0].targetService")).isEqualTo("payment-api");
    assertThat(detail.read("$.runbooks[0].recommended")).isEqualTo(true);
    assertThat(detail.read("$.runbooks[1].targetService")).isEqualTo("checkout-api");

    long paymentId = get("/api/incidents?status=active&service=payment-api").id("$[0].id");
    Reply payment = get("/api/incidents/" + paymentId + "/probable-source");
    assertThat(payment.read("$.verdict")).isEqualTo("SELF");
    assertThat(payment.read("$.service")).isEqualTo("payment-api");
    assertThat(payment.read("$.related[0].relation")).isEqualTo("dependent");

    Reply dashboard = get("/api/dashboard");
    assertThat(dashboard.read("$.health")).isEqualTo("DEGRADED");
    assertThat(dashboard.read("$.services[1].health")).isEqualTo("DEGRADED");
  }

  @Test
  @DisplayName("UC-03 alt: without payment telemetry the probable source is inconclusive, not guessed")
  void uc03_missingDependencyTelemetryIsInconclusive() {
    TELEMETRY.payment.latency = null;
    TELEMETRY.payment.errorRate = null;
    long id = openLatencyIncident(1.6);

    Reply detail = get("/api/incidents/" + id);
    assertThat(detail.read("$.probableSource.verdict")).isEqualTo("INCONCLUSIVE");
    assertThat(detail.read("$.probableSource.service")).isNull();
    assertThat((String) detail.read("$.probableSource.statement")).contains("payment-api");
    assertThat(get("/api/dashboard").read("$.services[1].health")).isEqualTo("UNKNOWN");
  }

  @Test
  @DisplayName("FR-09: a stuck service cannot be cleared from the dashboard; the refusal is audited")
  void fr09_stuckNeedsRestart() {
    Reply stuck = post("/api/demo/fault", "{\"service\":\"payment-api\",\"mode\":\"stuck\"}");
    assertThat(stuck.status()).isEqualTo(200);
    assertThat(stuck.read("$.needsRestart")).isEqualTo(true);
    assertThat(TELEMETRY.payment.faultMode).isEqualTo("stuck");

    Reply clear = post("/api/demo/fault", "{\"service\":\"payment-api\",\"mode\":\"none\"}");
    assertThat(clear.status()).isEqualTo(409);
    assertThat(clear.contentType()).contains("application/problem+json");
    assertThat((String) clear.read("$.detail")).contains("only a restart");
    assertThat(TELEMETRY.payment.faultMode).isEqualTo("stuck");

    Reply audit = get("/api/audit?limit=2");
    assertThat(audit.read("$[0].action")).isEqualTo("FAULT_CLEARED");
    assertThat(audit.read("$[0].result")).isEqualTo("FAILURE");
    assertThat(audit.read("$[0].target")).isEqualTo("service:payment-api");
    assertThat(audit.read("$[1].action")).isEqualTo("FAULT_INJECTED");

    assertThat(get("/api/demo/faults").read("$[1].needsRestart")).isEqualTo(true);
    assertThat(post("/api/demo/fault", "{\"service\":\"nope\",\"mode\":\"none\"}").status())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("FR-10/NFR-02: in Docker Compose mode remediation is offered but refused, and nothing changes")
  void fr10_nfr02_composeModeRefusesRemediation() {
    long id = openLatencyIncident(1.6);

    Reply detail = get("/api/incidents/" + id);
    assertThat(detail.read("$.runbooks[0].available")).isEqualTo(false);
    assertThat((String) detail.read("$.runbooks[0].unavailableReason")).contains("Docker Compose");
    assertThat(get("/api/remediation").read("$.enabled")).isEqualTo(false);
    assertThat(get("/api/dashboard").read("$.remediation.mode")).isEqualTo("disabled");

    Reply refused =
        post(
            "/api/incidents/" + id + "/remediations/RESTART_DEPLOYMENT/approve",
            "{\"operator\":\"Abhyudai\",\"confirm\":true}");
    assertThat(refused.status()).isEqualTo(409);
    assertThat(get("/api/incidents/" + id + "/remediations").read("$.length()")).isEqualTo(0);
    assertThat(STUB.requests().stream().anyMatch(r -> r.path().startsWith("/apis/"))).isFalse();
  }
}
