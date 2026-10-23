package edu.cloudpulse.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.cloudpulse.api.support.TestRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeverityPolicyTest {

  @Test
  @DisplayName("FR-03: latency severity bands follow the ratio to the 1.0 s threshold")
  void fr03_latencySeverityBands() {
    RuleDefinition latency = TestRules.latency();

    assertThat(latency.severityFor(1.0)).isEqualTo(Severity.MEDIUM);
    assertThat(latency.severityFor(1.42)).isEqualTo(Severity.MEDIUM);
    assertThat(latency.severityFor(1.5)).isEqualTo(Severity.HIGH);
    assertThat(latency.severityFor(1.99)).isEqualTo(Severity.HIGH);
    assertThat(latency.severityFor(2.0)).isEqualTo(Severity.CRITICAL);
    assertThat(latency.severityFor(2.46)).isEqualTo(Severity.CRITICAL);
  }

  @Test
  @DisplayName("FR-03: error-rate severity: 10 % is HIGH, 20 % and above is CRITICAL")
  void fr03_errorRateSeverityBands() {
    RuleDefinition errors = TestRules.errorRate();

    assertThat(errors.severityFor(0.06)).isEqualTo(Severity.MEDIUM);
    assertThat(errors.severityFor(0.10)).isEqualTo(Severity.HIGH);
    assertThat(errors.severityFor(0.30)).isEqualTo(Severity.CRITICAL);
  }

  @Test
  @DisplayName("FR-03: availability incidents are always CRITICAL")
  void fr03_fixedSeverityForAvailability() {
    assertThat(TestRules.availability().severityFor(0)).isEqualTo(Severity.CRITICAL);
  }

  @Test
  @DisplayName("NFR-10: invalid severity bands are rejected at startup")
  void nfr10_invalidBandsRejected() {
    assertThatThrownBy(() -> SeverityPolicy.bands(2.0, 1.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SeverityPolicy.bands(0.5, 2.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Severity ordering supports escalate-only incidents")
  void severityOrdering() {
    assertThat(Severity.CRITICAL.isHigherThan(Severity.HIGH)).isTrue();
    assertThat(Severity.HIGH.isHigherThan(Severity.HIGH)).isFalse();
    assertThat(Severity.MEDIUM.isHigherThan(null)).isTrue();
  }
}
