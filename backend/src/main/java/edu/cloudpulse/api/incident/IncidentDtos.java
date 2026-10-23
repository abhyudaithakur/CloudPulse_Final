package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.audit.AuditEventDto;
import edu.cloudpulse.api.correlation.ProbableSource;
import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.Severity;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationView;
import edu.cloudpulse.api.remediation.RemediationDtos.RunbookOption;
import java.time.Instant;
import java.util.List;

/** JSON shapes for the incident API. Entities never leave the service layer. */
public final class IncidentDtos {

  private IncidentDtos() {}

  public record IncidentSummary(
      long id,
      String key,
      String ruleId,
      String serviceName,
      String title,
      IncidentStatus status,
      Severity severity,
      MetricUnit unit,
      Comparison comparison,
      double threshold,
      double lastValue,
      double peakValue,
      long sustainSeconds,
      long recoverySeconds,
      Instant breachStartedAt,
      Instant openedAt,
      Instant resolvedAt,
      Instant lastObservedAt,
      double detectionDelaySeconds,
      String summary,
      String probableSource,
      ProbableSource.Verdict correlation) {

    public static IncidentSummary from(Incident incident, ProbableSource source) {
      return new IncidentSummary(
          incident.getId(),
          incident.key(),
          incident.getRuleId(),
          incident.getServiceName(),
          incident.getTitle(),
          incident.getStatus(),
          incident.getSeverity(),
          incident.getUnit(),
          incident.getComparison(),
          incident.getThresholdValue(),
          incident.getLastValue(),
          incident.getPeakValue(),
          incident.getSustainSeconds(),
          incident.getRecoverySeconds(),
          incident.getBreachStartedAt(),
          incident.getOpenedAt(),
          incident.getResolvedAt(),
          incident.getLastObservedAt(),
          incident.detectionDelaySeconds(),
          incident.getSummary(),
          source == null ? null : source.service(),
          source == null ? null : source.verdict());
    }
  }

  public record EvidenceItem(
      long id,
      Instant observedAt,
      EvidenceKind kind,
      String metric,
      double observedValue,
      double threshold,
      MetricUnit unit,
      String source,
      String promql,
      String note) {

    public static EvidenceItem from(Evidence evidence) {
      return new EvidenceItem(
          evidence.getId(),
          evidence.getObservedAt(),
          evidence.getKind(),
          evidence.getMetric(),
          evidence.getObservedValue(),
          evidence.getThresholdValue(),
          evidence.getUnit(),
          evidence.getSource(),
          evidence.getPromql(),
          evidence.getNote());
    }
  }

  /**
   * Everything the incident page shows (FR-07): the incident, its evidence, the audit timeline,
   * the probable source (FR-08), the runbooks that may be approved and past remediations.
   */
  public record IncidentDetail(
      IncidentSummary incident,
      List<EvidenceItem> evidence,
      List<AuditEventDto> timeline,
      ProbableSource probableSource,
      List<RunbookOption> runbooks,
      List<RemediationView> remediations) {}
}
