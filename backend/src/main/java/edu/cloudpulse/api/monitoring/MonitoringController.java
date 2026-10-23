package edu.cloudpulse.api.monitoring;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toSet;

import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.correlation.CorrelationService;
import edu.cloudpulse.api.correlation.ProbableSource;
import edu.cloudpulse.api.demo.DemoServiceClient;
import edu.cloudpulse.api.detection.DetectionEngine;
import edu.cloudpulse.api.detection.PipelineSnapshot;
import edu.cloudpulse.api.detection.RuleCatalog;
import edu.cloudpulse.api.detection.RuleSnapshot;
import edu.cloudpulse.api.domain.MonitoredService;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.ServiceTopology;
import edu.cloudpulse.api.incident.Incident;
import edu.cloudpulse.api.incident.IncidentDtos.IncidentSummary;
import edu.cloudpulse.api.incident.IncidentService;
import edu.cloudpulse.api.monitoring.MonitoringViews.Counts;
import edu.cloudpulse.api.monitoring.MonitoringViews.DashboardView;
import edu.cloudpulse.api.monitoring.MonitoringViews.SeriesView;
import edu.cloudpulse.api.monitoring.MonitoringViews.ServiceView;
import edu.cloudpulse.api.remediation.RemediationService;
import edu.cloudpulse.api.telemetry.PrometheusClient;
import edu.cloudpulse.api.telemetry.SeriesPoint;
import edu.cloudpulse.api.telemetry.TelemetryUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Service health, rule status and the dashboard aggregate (FR-06, UC-01). */
@RestController
@RequestMapping("/api")
public class MonitoringController {

  private static final int DASHBOARD_INCIDENTS = 25;

  private final DetectionEngine engine;
  private final RuleCatalog catalog;
  private final ServiceTopology topology;
  private final IncidentService incidents;
  private final CorrelationService correlation;
  private final RemediationService remediation;
  private final PrometheusClient prometheus;
  private final DemoServiceClient demo;
  private final Clock clock;
  private final String prometheusUrl;

  public MonitoringController(
      DetectionEngine engine,
      RuleCatalog catalog,
      ServiceTopology topology,
      IncidentService incidents,
      CorrelationService correlation,
      RemediationService remediation,
      PrometheusClient prometheus,
      DemoServiceClient demo,
      Clock clock,
      CloudPulseProperties properties) {
    this.engine = engine;
    this.catalog = catalog;
    this.topology = topology;
    this.incidents = incidents;
    this.correlation = correlation;
    this.remediation = remediation;
    this.prometheus = prometheus;
    this.demo = demo;
    this.clock = clock;
    this.prometheusUrl = properties.prometheus().uiUrl().toString();
  }

  /** Monitored services with current health and telemetry-aware status. */
  @GetMapping("/services")
  public List<ServiceView> services() {
    return services(engine.pipeline(), engine.rules(), incidents.active());
  }

  /** Every configured rule, per service, with its latest evaluation. */
  @GetMapping("/rules")
  public List<RuleSnapshot> rules() {
    return engine.rules();
  }

  /** Recent history of one service's rule metric from Prometheus, for charts. Gaps are null. */
  @GetMapping("/services/{service}/rules/{ruleId}/series")
  public SeriesView series(
      @PathVariable String service,
      @PathVariable String ruleId,
      @RequestParam(defaultValue = "5") int minutes) {
    RuleDefinition rule =
        catalog
            .find(service, ruleId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown rule " + ruleId + " for service " + service));
    int window = Math.max(1, Math.min(minutes, 30));
    Instant end = clock.instant();
    Instant start = end.minus(Duration.ofMinutes(window));
    long stepSeconds = Math.max(5, window * 60L / 120);
    try {
      List<SeriesPoint> points =
          prometheus.range(rule.query(), start, end, Duration.ofSeconds(stepSeconds));
      return new SeriesView(
          service, rule.id(), rule.unit(), rule.threshold(), start, end, stepSeconds, "OK", "", points);
    } catch (TelemetryUnavailableException e) {
      return new SeriesView(
          service,
          rule.id(),
          rule.unit(),
          rule.threshold(),
          start,
          end,
          stepSeconds,
          "UNAVAILABLE",
          e.getMessage(),
          List.of());
    }
  }

  /** One-poll aggregate for the overview screen. */
  @GetMapping("/dashboard")
  public DashboardView dashboard() {
    PipelineSnapshot pipeline = engine.pipeline();
    List<RuleSnapshot> rules = engine.rules();
    List<Incident> active = incidents.active();
    List<Incident> recentIncidents = incidents.search(null, null, null, DASHBOARD_INCIDENTS);
    Map<Long, ProbableSource> sources = correlation.forIncidents(recentIncidents);
    List<IncidentSummary> recent =
        recentIncidents.stream().map(i -> IncidentSummary.from(i, sources.get(i.getId()))).toList();
    List<ServiceView> services = services(pipeline, rules, active);
    return new DashboardView(
        clock.instant(),
        HealthCalculator.overall(services.stream().map(ServiceView::health).toList()),
        pipeline,
        services,
        rules,
        new Counts(active.size(), incidents.countAll()),
        topology.all().stream().map(demo::current).toList(),
        recent,
        remediation.config(),
        prometheusUrl);
  }

  private List<ServiceView> services(
      PipelineSnapshot pipeline, List<RuleSnapshot> rules, List<Incident> active) {
    Map<String, Set<String>> activeRulesByService =
        active.stream()
            .collect(groupingBy(Incident::getServiceName, mapping(Incident::getRuleId, toSet())));
    return topology.all().stream()
        .map(
            (MonitoredService service) -> {
              List<RuleSnapshot> serviceRules =
                  rules.stream().filter(r -> r.serviceName().equals(service.name())).toList();
              Set<String> activeRules = activeRulesByService.getOrDefault(service.name(), Set.of());
              return new ServiceView(
                  service.name(),
                  HealthCalculator.healthOf(pipeline.status(), serviceRules, activeRules),
                  activeRules.size(),
                  serviceRules.stream().map(RuleSnapshot::ruleId).toList(),
                  service.dependsOn(),
                  service.deployment());
            })
        .toList();
  }
}
