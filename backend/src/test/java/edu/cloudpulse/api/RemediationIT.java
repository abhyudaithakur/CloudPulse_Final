package edu.cloudpulse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cloudpulse.api.remediation.RemediationService;
import edu.cloudpulse.api.support.FakeKubernetes;
import edu.cloudpulse.api.support.StubHttpServer.Request;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Guarded remediation end to end (FR-10 to FR-12, NFR-02, NFR-03, UC-04): approval over HTTP,
 * the real Kubernetes client against a scripted API server, every step persisted in PostgreSQL
 * and audited, and recovery verified against the incident.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiITSupport.TestClockConfig.class)
class RemediationIT extends ApiITSupport {

  private static final String TOKEN = "test-service-account-token";
  private static final Path TOKEN_FILE = tokenFile();

  private static Path tokenFile() {
    try {
      Path file = Files.createTempFile("cloudpulse-sa-token", "");
      Files.writeString(file, TOKEN + "\n");
      file.toFile().deleteOnExit();
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void remediationProperties(DynamicPropertyRegistry registry) {
    registry.add("cloudpulse.remediation.enabled", () -> "true");
    registry.add("cloudpulse.remediation.kubernetes.api-url", STUB::baseUrl);
    registry.add("cloudpulse.remediation.kubernetes.token-path", TOKEN_FILE::toString);
  }

  @Autowired private RemediationService remediation;

  private static final String APPROVAL =
      "{\"operator\":\"Abhyudai\",\"confirm\":true,\"note\":\"payments look wedged\"%s}";

  private Reply approve(long incidentId, String extra) {
    return post(
        "/api/incidents/" + incidentId + "/remediations/RESTART_DEPLOYMENT/approve",
        String.format(APPROVAL, extra));
  }

  private String status(long incidentId) {
    return (String) get("/api/incidents/" + incidentId + "/remediations").read("$[0].status");
  }

  private List<Request> patches(String deployment) {
    return STUB.requests(FakeKubernetes.path(deployment)).stream()
        .filter(r -> r.method().equals("PATCH"))
        .toList();
  }

  @Test
  @DisplayName("FR-10/FR-11/FR-12/UC-04: an approved restart runs once, rolls out and is verified as recovered")
  void fr10_fr11_fr12_uc04_approvedRestartRecovers() {
    TELEMETRY.checkout.faultMode = "stuck";
    KUBERNETES.onRestart = name -> TELEMETRY.service(name).restarted();
    long id = openLatencyIncident(3.0);

    Reply detail = get("/api/incidents/" + id);
    assertThat(detail.read("$.runbooks[0].available")).isEqualTo(true);
    assertThat(get("/api/remediation").read("$.mode")).isEqualTo("kubernetes");

    Reply approved = approve(id, "");
    assertThat(approved.status()).isEqualTo(202);
    assertThat(approved.read("$.status")).isEqualTo("APPROVED");
    assertThat(approved.read("$.target")).isEqualTo("deployment:cloudpulse-demo/checkout-api");
    assertThat(patches("checkout-api")).isEmpty(); // approval alone changes nothing

    remediation.tick(); // execute
    assertThat(status(id)).isEqualTo("ROLLING_OUT");
    List<Request> sent = patches("checkout-api");
    assertThat(sent).hasSize(1);
    Request patch = sent.get(0);
    assertThat(patch.header("authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(patch.header("content-type")).startsWith("application/strategic-merge-patch+json");
    assertThat(patch.body()).contains("kubectl.kubernetes.io/restartedAt");
    assertThat(patch.body()).contains("approved-by-abhyudai");
    assertThat(patches("payment-api")).isEmpty();

    remediation.tick(); // rollout finished
    assertThat(status(id)).isEqualTo("VERIFYING");

    cycles(3); // the restarted service is healthy for the 10 s recovery window
    assertThat(get("/api/incidents/" + id).read("$.incident.status")).isEqualTo("RESOLVED");
    remediation.tick(); // verify
    Reply done = get("/api/incidents/" + id + "/remediations");
    assertThat(done.read("$[0].status")).isEqualTo("RECOVERED");
    assertThat(done.read("$[0].inProgress")).isEqualTo(false);
    assertThat((String) done.read("$[0].detail")).startsWith("Recovered:");

    remediation.tick();
    assertThat(patches("checkout-api")).hasSize(1); // never executed twice

    List<String> actions = get("/api/incidents/" + id + "/audit").json().read("$[*].action");
    assertThat(actions)
        .containsSubsequence(
            "INCIDENT_OPENED",
            "REMEDIATION_APPROVED",
            "REMEDIATION_EXECUTED",
            "REMEDIATION_ROLLED_OUT",
            "INCIDENT_RESOLVED",
            "REMEDIATION_VERIFIED");
    List<String> actors = get("/api/incidents/" + id + "/audit").json().read("$[*].actor");
    assertThat(actors).contains("operator:Abhyudai", "remediator", "detector");
  }

  @Test
  @DisplayName("FR-11/NFR-02: approvals need a named operator, explicit confirmation and an allowed target")
  void fr11_nfr02_approvalIsGuarded() {
    long id = openLatencyIncident(1.6);
    String path = "/api/incidents/" + id + "/remediations/RESTART_DEPLOYMENT/approve";

    assertThat(post(path, "{\"operator\":\"Abhyudai\"}").status()).isEqualTo(400);
    assertThat(post(path, "{\"operator\":\"Abhyudai\",\"confirm\":false}").status()).isEqualTo(400);
    assertThat(post(path, "{\"operator\":\"  \",\"confirm\":true}").status()).isEqualTo(400);
    assertThat(post(path, "").status()).isEqualTo(400);
    assertThat(
            post("/api/incidents/" + id + "/remediations/DELETE_NAMESPACE/approve",
                    "{\"operator\":\"Abhyudai\",\"confirm\":true}")
                .status())
        .isEqualTo(404);
    assertThat(approve(999, "").status()).isEqualTo(404);
    Reply outside = approve(id, ",\"targetService\":\"cloudpulse-api\"");
    assertThat(outside.status()).isEqualTo(400);
    assertThat((String) outside.read("$.detail")).contains("may target only");

    assertThat(approve(id, "").status()).isEqualTo(202);
    Reply second = approve(id, "");
    assertThat(second.status()).isEqualTo(409);
    assertThat((String) second.read("$.detail")).contains("already in progress");
    assertThat(get("/api/incidents/" + id + "/remediations").read("$.length()")).isEqualTo(1);
    assertThat(STUB.requests().stream().anyMatch(r -> r.method().equals("PATCH"))).isFalse();
  }

  @Test
  @DisplayName("FR-08/FR-10: the probable source (a dependency) may be restarted for a checkout incident")
  void fr08_fr10_restartTheProbableSource() {
    TELEMETRY.payment.errorRate = 0.6;
    cycles(2);
    TELEMETRY.checkout.errorRate = 0.6;
    cycles(7);
    long checkoutId = get("/api/incidents?status=active&service=checkout-api").id("$[0].id");
    long paymentId = get("/api/incidents?status=active&service=payment-api").id("$[0].id");

    // payment-api does not call checkout-api, so restarting checkout for a payment incident is refused.
    assertThat(approve(paymentId, ",\"targetService\":\"checkout-api\"").status()).isEqualTo(400);

    assertThat(approve(checkoutId, ",\"targetService\":\"payment-api\"").status()).isEqualTo(202);
    remediation.tick();
    assertThat(patches("payment-api")).hasSize(1);
    assertThat(patches("checkout-api")).isEmpty();
  }

  @Test
  @DisplayName("UC-04 alt/NFR-03: a restart Kubernetes forbids fails cleanly and the incident stays active")
  void uc04_nfr03_forbiddenRestartFails() {
    KUBERNETES.forbidden = true;
    long id = openLatencyIncident(1.6);
    assertThat(approve(id, "").status()).isEqualTo(202);

    remediation.tick();
    Reply result = get("/api/incidents/" + id + "/remediations");
    assertThat(result.read("$[0].status")).isEqualTo("FAILED");
    assertThat((String) result.read("$[0].detail"))
        .contains("HTTP 403")
        .contains("cannot patch resource")
        .contains("incident stays active");
    assertThat(get("/api/incidents/" + id).read("$.incident.status")).isEqualTo("ACTIVE");

    Reply audit = get("/api/incidents/" + id + "/audit");
    assertThat(audit.read("$[-1].action")).isEqualTo("REMEDIATION_EXECUTED");
    assertThat(audit.read("$[-1].result")).isEqualTo("FAILURE");

    // A failed attempt is finished, so the operator may approve again.
    KUBERNETES.forbidden = false;
    assertThat(approve(id, "").status()).isEqualTo(202);
  }

  @Test
  @DisplayName("FR-12/UC-05 alt: if the condition persists after the restart, it is NOT_RECOVERED and stays open")
  void fr12_notRecoveredWhenConditionPersists() {
    long id = openLatencyIncident(1.6); // a restart does not fix this fault
    approve(id, "");
    remediation.tick();
    remediation.tick();
    assertThat(status(id)).isEqualTo("VERIFYING");

    cycles(6);
    remediation.tick();
    assertThat(status(id)).isEqualTo("VERIFYING"); // still inside the 150 s window

    clock.advance(Duration.ofSeconds(150));
    remediation.tick();
    assertThat(status(id)).isEqualTo("NOT_RECOVERED");
    assertThat(get("/api/incidents/" + id).read("$.incident.status")).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("UC-04 alt: a rollout that never completes fails after the rollout timeout")
  void uc04_rolloutTimeout() {
    KUBERNETES.rolloutCompletes = false;
    long id = openLatencyIncident(1.6);
    approve(id, "");
    remediation.tick();
    remediation.tick();
    assertThat(status(id)).isEqualTo("ROLLING_OUT");

    clock.advance(Duration.ofSeconds(181));
    remediation.tick();
    assertThat(status(id)).isEqualTo("FAILED");
    assertThat((String) get("/api/incidents/" + id + "/remediations").read("$[0].detail"))
        .contains("did not complete within 180 s");
  }

  @Test
  @DisplayName("NFR-02: an attempt interrupted mid-call is marked FAILED and never re-sent automatically")
  void nfr02_interruptedAttemptIsNotRetried() {
    long id = openLatencyIncident(1.6);
    long action = approve(id, "").id("$.id");
    // As if the API had stopped while the PATCH was in flight.
    jdbc.update("UPDATE remediation_actions SET status = 'EXECUTING' WHERE id = ?", action);

    remediation.tick();
    assertThat(status(id)).isEqualTo("FAILED");
    assertThat((String) get("/api/incidents/" + id + "/remediations").read("$[0].detail"))
        .contains("Not retried automatically");
    assertThat(patches("checkout-api")).isEmpty();
  }

  @Test
  @DisplayName("NFR-02: the database allows only one remediation in progress per incident")
  void nfr02_oneInProgressPerIncidentInTheDatabase() {
    long id = openLatencyIncident(1.6);
    approve(id, "");
    String insert =
        "INSERT INTO remediation_actions (incident_id, runbook, target_service, namespace, deployment,"
            + " approved_by, approval_note, status, approved_at, detail)"
            + " VALUES (?, 'RESTART_DEPLOYMENT', 'checkout-api', 'cloudpulse-demo', 'checkout-api',"
            + " 'someone', '', ?, now(), '')";

    assertThatThrownBy(() -> jdbc.update(insert, id, "VERIFYING"))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(insert, id, "FAILED"); // finished rows are not limited
    assertThatThrownBy(() -> jdbc.update(insert, id, "DELETED"))
        .isInstanceOf(DataIntegrityViolationException.class); // CHECK constraint
  }
}
