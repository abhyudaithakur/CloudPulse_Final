package edu.cloudpulse.api.correlation;

import edu.cloudpulse.api.domain.Severity;
import edu.cloudpulse.api.incident.IncidentStatus;
import java.time.Instant;
import java.util.List;

/**
 * Where an incident probably started (RAD FR-08, UC-03). Deliberately worded as "probable": it is
 * inferred from the configured dependency map and the order in which problems began, which is
 * evidence, not proof of root cause.
 *
 * @param verdict DEPENDENCY (a service it calls), SELF (the incident's own service) or
 *     INCONCLUSIVE (not enough evidence: UC-03's alternative flow)
 * @param service the probable source; null when inconclusive
 * @param statement one sentence for the operator
 * @param evidence the facts behind the statement
 * @param related incidents on dependencies and on services affected downstream
 */
public record ProbableSource(
    Verdict verdict,
    String service,
    String statement,
    List<String> evidence,
    List<RelatedIncident> related) {

  public enum Verdict {
    DEPENDENCY,
    SELF,
    INCONCLUSIVE
  }

  /**
   * @param relation "dependency" when the incident's service calls this one, "dependent" when
   *     this service calls the incident's service (it is affected by it)
   */
  public record RelatedIncident(
      long id,
      String key,
      String serviceName,
      String ruleId,
      String title,
      String relation,
      IncidentStatus status,
      Severity severity,
      Instant breachStartedAt) {}
}
