package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.domain.RuleDefinition;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** Builds incidents for tests outside this package (the lifecycle methods are package-private). */
public final class TestIncidents {

  private TestIncidents() {}

  /** An ACTIVE incident whose breach began at {@code breachStartedAt}, opened one window later. */
  public static Incident active(long id, RuleDefinition rule, Instant breachStartedAt, double value) {
    Incident incident =
        Incident.open(
            rule, breachStartedAt, breachStartedAt.plus(rule.sustain()), value, "test incident");
    ReflectionTestUtils.setField(incident, "id", id);
    return incident;
  }

  public static Incident resolved(
      long id, RuleDefinition rule, Instant breachStartedAt, Instant resolvedAt, double value) {
    Incident incident = active(id, rule, breachStartedAt, value);
    incident.resolve(resolvedAt, value);
    return incident;
  }
}
