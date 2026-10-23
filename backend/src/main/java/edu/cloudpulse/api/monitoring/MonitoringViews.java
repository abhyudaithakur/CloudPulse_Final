package edu.cloudpulse.api.monitoring;

import edu.cloudpulse.api.demo.DemoServiceClient.FaultState;
import edu.cloudpulse.api.detection.PipelineSnapshot;
import edu.cloudpulse.api.detection.RuleSnapshot;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.incident.IncidentDtos.IncidentSummary;
import edu.cloudpulse.api.remediation.RemediationDtos.RemediationConfig;
import edu.cloudpulse.api.telemetry.SeriesPoint;
import java.time.Instant;
import java.util.List;

/** JSON shapes for service health, rules and the dashboard. */
public final class MonitoringViews {

  private MonitoringViews() {}

  /**
   * @param dependsOn services this one calls (the dependency map, FR-08)
   * @param deployment the Kubernetes deployment a restart runbook would target
   */
  public record ServiceView(
      String name,
      ServiceHealth health,
      long activeIncidents,
      List<String> ruleIds,
      List<String> dependsOn,
      String deployment) {}

  public record SeriesView(
      String service,
      String ruleId,
      MetricUnit unit,
      double threshold,
      Instant start,
      Instant end,
      long stepSeconds,
      String status,
      String detail,
      List<SeriesPoint> points) {}

  public record Counts(long active, long total) {}

  /** Everything the overview needs in one poll. */
  public record DashboardView(
      Instant generatedAt,
      ServiceHealth health,
      PipelineSnapshot pipeline,
      List<ServiceView> services,
      List<RuleSnapshot> rules,
      Counts counts,
      List<FaultState> faults,
      List<IncidentSummary> incidents,
      RemediationConfig remediation,
      String prometheusUrl) {}
}
