package edu.cloudpulse.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cloudpulse.api.correlation.ProbableSource.Verdict;
import edu.cloudpulse.api.domain.MonitoredService;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.incident.Incident;
import edu.cloudpulse.api.incident.TestIncidents;
import edu.cloudpulse.api.support.TestRules;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-08 / UC-03: probable source from the dependency map and the order problems began in. */
class CorrelationEngineTest {

  private static final Instant T0 = Instant.parse("2026-10-21T15:00:00Z");

  private static MonitoredService service(String name, String... dependsOn) {
    return new MonitoredService(name, URI.create("http://" + name), List.of(dependsOn), null);
  }

  private final ServiceTopology topology =
      new ServiceTopology(
          List.of(
              service("storefront", "checkout-api"),
              service("checkout-api", "payment-api"),
              service("payment-api")));
  private final CorrelationEngine engine = new CorrelationEngine(topology, Duration.ofSeconds(30));

  private final Instant now = T0.plusSeconds(300);

  @Test
  @DisplayName("FR-08: a dependency whose problem started first is the probable source")
  void fr08_dependencyStartedFirst() {
    Incident payment = TestIncidents.active(1, TestRules.errorRate("payment-api"), T0, 0.9);
    Incident checkout =
        TestIncidents.active(2, TestRules.errorRate("checkout-api"), T0.plusSeconds(5), 0.8);

    ProbableSource source = engine.analyze(checkout, List.of(payment), Set.of(), now);

    assertThat(source.verdict()).isEqualTo(Verdict.DEPENDENCY);
    assertThat(source.service()).isEqualTo("payment-api");
    assertThat(source.statement())
        .startsWith("Probable source: payment-api.")
        .contains("not proof of root cause");
    assertThat(source.evidence())
        .anySatisfy(line -> assertThat(line).contains("checkout-api → payment-api"))
        .anySatisfy(line -> assertThat(line).contains("5 s before checkout-api's"));
    assertThat(source.related()).singleElement().satisfies(r -> {
      assertThat(r.relation()).isEqualTo("dependency");
      assertThat(r.key()).isEqualTo("CP-0001");
    });
  }

  @Test
  @DisplayName("FR-08: a dependency that broke slightly later (within tolerance) still counts")
  void fr08_dependencyWithinTolerance() {
    // Different rules have different windows, so the dependency's breach can be detected later.
    Incident checkout = TestIncidents.active(2, TestRules.latency("checkout-api"), T0, 2.5);
    Incident payment =
        TestIncidents.active(1, TestRules.latency("payment-api"), T0.plusSeconds(20), 3.0);

    ProbableSource source = engine.analyze(checkout, List.of(payment), Set.of(), now);

    assertThat(source.verdict()).isEqualTo(Verdict.DEPENDENCY);
    assertThat(source.service()).isEqualTo("payment-api");
    assertThat(source.statement()).contains("within the correlation window");
  }

  @Test
  @DisplayName("FR-08: a dependency that broke well after this incident is an effect, not the cause")
  void fr08_laterDependencyIsNotTheSource() {
    Incident checkout = TestIncidents.active(2, TestRules.latency("checkout-api"), T0, 2.5);
    Incident payment =
        TestIncidents.active(1, TestRules.latency("payment-api"), T0.plusSeconds(90), 3.0);

    ProbableSource source = engine.analyze(checkout, List.of(payment), Set.of(), now);

    assertThat(source.verdict()).isEqualTo(Verdict.SELF);
    assertThat(source.service()).isEqualTo("checkout-api");
    assertThat(source.evidence())
        .anySatisfy(line -> assertThat(line).contains("more likely an effect than the cause"));
  }

  @Test
  @DisplayName("FR-08: with a chain of failures, the service furthest downstream wins")
  void fr08_deepestDependencyWins() {
    Incident payment = TestIncidents.active(1, TestRules.errorRate("payment-api"), T0, 0.9);
    Incident checkout =
        TestIncidents.active(2, TestRules.errorRate("checkout-api"), T0.plusSeconds(5), 0.9);
    Incident storefront =
        TestIncidents.active(3, TestRules.errorRate("storefront"), T0.plusSeconds(10), 0.9);

    ProbableSource source =
        engine.analyze(storefront, List.of(checkout, payment), Set.of(), now);

    assertThat(source.service()).isEqualTo("payment-api");
    assertThat(source.evidence())
        .anySatisfy(line -> assertThat(line).contains("storefront → checkout-api → payment-api"));
  }

  @Test
  @DisplayName("UC-03: the source incident lists the services it affected")
  void uc03_sourceListsAffectedServices() {
    Incident payment = TestIncidents.active(1, TestRules.errorRate("payment-api"), T0, 0.9);
    Incident checkout =
        TestIncidents.active(2, TestRules.errorRate("checkout-api"), T0.plusSeconds(5), 0.9);

    ProbableSource source = engine.analyze(payment, List.of(checkout), Set.of(), now);

    assertThat(source.verdict()).isEqualTo(Verdict.SELF);
    assertThat(source.service()).isEqualTo("payment-api");
    assertThat(source.statement()).contains("calls no other monitored service");
    assertThat(source.related()).singleElement().satisfies(r ->
        assertThat(r.relation()).isEqualTo("dependent"));
    assertThat(source.evidence())
        .anySatisfy(line -> assertThat(line).startsWith("Also affected: checkout-api"));
  }

  @Test
  @DisplayName("UC-03 alt: a dependency without telemetry makes the result inconclusive")
  void uc03_blindDependencyIsInconclusive() {
    Incident checkout = TestIncidents.active(2, TestRules.latency("checkout-api"), T0, 2.5);

    ProbableSource source = engine.analyze(checkout, List.of(), Set.of("payment-api"), now);

    assertThat(source.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
    assertThat(source.service()).isNull();
    assertThat(source.statement()).contains("cannot be ruled out");
  }

  @Test
  @DisplayName("FR-08: incidents that did not overlap in time are ignored")
  void fr08_nonOverlappingIgnored() {
    Incident oldPayment =
        TestIncidents.resolved(
            1, TestRules.errorRate("payment-api"), T0.minusSeconds(600), T0.minusSeconds(400), 0.9);
    Incident checkout = TestIncidents.active(2, TestRules.errorRate("checkout-api"), T0, 0.9);

    ProbableSource source = engine.analyze(checkout, List.of(oldPayment), Set.of(), now);

    assertThat(source.verdict()).isEqualTo(Verdict.SELF);
    assertThat(source.related()).isEmpty();
  }

  @Test
  @DisplayName("FR-08: a resolved incident keeps its probable source when telemetry is gone later")
  void fr08_resolvedIncidentIsNotInconclusive() {
    Incident payment =
        TestIncidents.resolved(1, TestRules.errorRate("payment-api"), T0, T0.plusSeconds(120), 0.9);
    Incident checkout =
        TestIncidents.resolved(
            2, TestRules.errorRate("checkout-api"), T0.plusSeconds(5), T0.plusSeconds(125), 0.9);

    ProbableSource source =
        engine.analyze(checkout, List.of(payment), Set.of("payment-api"), now);

    assertThat(source.verdict()).isEqualTo(Verdict.DEPENDENCY);
    assertThat(source.service()).isEqualTo("payment-api");
  }
}
