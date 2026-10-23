package edu.cloudpulse.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cloudpulse.api.config.RuleProperties;
import edu.cloudpulse.api.detection.RuleCatalog;
import edu.cloudpulse.api.support.TestRules;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleDefinitionTest {

  @Test
  @DisplayName("NFR-10: rules are built from configuration, with seconds as the default unit")
  void nfr10_ruleBuiltFromConfiguration() {
    RuleProperties properties =
        new RuleProperties(
            "LATENCY_P95_SUSTAINED",
            "Sustained high request latency",
            "max(latency{application=\"{service}\"})",
            Comparison.ABOVE,
            1.0,
            MetricUnit.SECONDS,
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            new RuleProperties.SeverityProperties(null, 1.5, 2.0));

    RuleDefinition rule = properties.toDefinition("payment-api");

    assertThat(rule.serviceName()).isEqualTo("payment-api");
    assertThat(rule.key()).isEqualTo("payment-api/LATENCY_P95_SUSTAINED");
    assertThat(rule.query()).isEqualTo("max(latency{application=\"payment-api\"})");
    assertThat(rule.sustain()).isEqualTo(Duration.ofSeconds(30));
    assertThat(rule.isBreaching(1.0)).isTrue();
    assertThat(rule.isBreaching(0.99)).isFalse();
  }

  @Test
  @DisplayName("NFR-10: misconfigured rules fail fast with a clear message")
  void nfr10_invalidRulesRejected() {
    assertThatThrownBy(
            () ->
                new RuleProperties(
                        "BROKEN", "t", "q{service}", Comparison.ABOVE, null, MetricUnit.SECONDS,
                        Duration.ZERO, Duration.ZERO, null)
                    .toDefinition("svc"))
        .hasMessageContaining("threshold is required");
    assertThatThrownBy(
            () ->
                new RuleProperties(
                        "GLOBAL", "t", "up", Comparison.BELOW, 1.0, MetricUnit.BOOLEAN,
                        Duration.ZERO, Duration.ZERO, null)
                    .toDefinition("svc"))
        .hasMessageContaining("{service}");

    RuleDefinition rule = TestRules.latency();
    assertThatThrownBy(() -> new RuleCatalog(List.of(rule, rule)))
        .hasMessageContaining("duplicate rule id");
    // The same rule type on two services is two rules, not a duplicate.
    assertThat(new RuleCatalog(List.of(rule, TestRules.latency("payment-api"))).all()).hasSize(2);
    assertThatThrownBy(() -> new RuleCatalog(List.of()))
        .hasMessageContaining("at least one rule");
  }

  @Test
  @DisplayName("FR-05: evidence text names the observed value, comparison and threshold")
  void fr05_describeIncludesValueAndThreshold() {
    assertThat(TestRules.latency().describe(1.4321)).isEqualTo("1.432 s ≥ 1.000 s");
    assertThat(TestRules.errorRate().describe(0.3)).isEqualTo("30.0% ≥ 5.0%");
    assertThat(TestRules.availability().describe(0)).isEqualTo("down < up");
  }
}
