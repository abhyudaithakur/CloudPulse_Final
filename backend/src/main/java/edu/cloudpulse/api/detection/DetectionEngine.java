package edu.cloudpulse.api.detection;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.detection.PipelineSnapshot.Status;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.incident.IncidentService;
import edu.cloudpulse.api.telemetry.PrometheusClient;
import edu.cloudpulse.api.telemetry.TelemetryReading;
import edu.cloudpulse.api.telemetry.TelemetryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Runs one detection cycle: query every rule (FR-01), evaluate it (FR-02), and hand the decision
 * to the incident module. Telemetry I/O happens outside any database transaction; each incident
 * change is its own short transaction.
 */
@Component
public class DetectionEngine {

  private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

  private final RuleCatalog catalog;
  private final PrometheusClient prometheus;
  private final SustainedThresholdEvaluator evaluator;
  private final IncidentService incidents;
  private final AuditService audit;
  private final Clock clock;
  private final MeterRegistry meters;

  private final Map<String, RuleWindow> windows = new ConcurrentHashMap<>();
  private final Map<String, RuleSnapshot> snapshots = new ConcurrentHashMap<>();
  private volatile PipelineSnapshot pipeline;

  public DetectionEngine(
      RuleCatalog catalog,
      PrometheusClient prometheus,
      SustainedThresholdEvaluator evaluator,
      IncidentService incidents,
      AuditService audit,
      Clock clock,
      MeterRegistry meters,
      CloudPulseProperties properties) {
    this.catalog = catalog;
    this.prometheus = prometheus;
    this.evaluator = evaluator;
    this.incidents = incidents;
    this.audit = audit;
    this.clock = clock;
    this.meters = meters;
    this.pipeline =
        PipelineSnapshot.starting(catalog.all().size(), properties.scheduling().intervalMs());
    catalog.all().forEach(rule -> snapshots.put(rule.key(), RuleSnapshot.starting(rule)));
  }

  /** One evaluation cycle over every configured rule. Safe to call from tests. */
  public synchronized void evaluateAll() {
    Instant now = clock.instant();
    List<RuleDefinition> rules = catalog.all();
    int failing = 0;
    String lastError = "";

    for (RuleDefinition rule : rules) {
      Timer.Sample timing = Timer.start(meters);
      TelemetryReading reading = prometheus.instant(rule.query(), rule.comparison());
      timing.stop(
          meters.timer(
              "cloudpulse.telemetry.query",
              "rule",
              rule.id(),
              "service",
              rule.serviceName(),
              "status",
              reading.status().name()));
      if (reading.status() == TelemetryStatus.UNAVAILABLE) {
        failing++;
        lastError = reading.detail();
      }

      try {
        boolean active = incidents.hasActive(rule);
        RuleWindow window = windows.computeIfAbsent(rule.key(), key -> new RuleWindow());
        Evaluation evaluation = evaluator.evaluate(rule, window, reading, active, now);
        apply(rule, evaluation, now);
        snapshots.put(rule.key(), RuleSnapshot.of(rule, evaluation, reading, now));
        meters
            .counter(
                "cloudpulse.evaluations",
                "rule",
                rule.id(),
                "service",
                rule.serviceName(),
                "phase",
                evaluation.phase().name())
            .increment();
      } catch (RuntimeException e) {
        log.error("Evaluation of rule {} failed; it will be retried next cycle", rule.key(), e);
      }
    }
    updatePipeline(now, failing, rules.size(), lastError);
  }

  private void apply(RuleDefinition rule, Evaluation evaluation, Instant now) {
    switch (evaluation.action()) {
      case OPEN -> {
        try {
          incidents.open(rule, evaluation.breachStartedAt(), now, evaluation.value());
        } catch (DataIntegrityViolationException e) {
          // The partial unique index rejected a second ACTIVE incident (e.g. another API replica
          // opened it first). Deduplication held at the database level; nothing else to do.
          log.warn("Rule {}: an active incident already exists (unique index)", rule.key());
        }
      }
      case UPDATE -> incidents.recordBreach(rule, now, evaluation.value());
      case RESOLVE ->
          incidents.resolve(rule, now, evaluation.value(), evaluation.recoveryStartedAt());
      case NONE -> {
        // nothing to persist
      }
    }
  }

  private void updatePipeline(Instant now, int failing, int total, String lastError) {
    PipelineSnapshot previous = pipeline;
    Status status;
    if (failing == 0) {
      status = Status.CONNECTED;
    } else if (failing < total) {
      status = Status.DEGRADED;
    } else {
      status = Status.UNAVAILABLE;
    }
    Instant lastSuccess = status == Status.UNAVAILABLE ? previous.lastSuccessAt() : now;
    pipeline =
        new PipelineSnapshot(
            status,
            previous.source(),
            now,
            lastSuccess,
            status == Status.CONNECTED ? "" : lastError,
            failing,
            total,
            previous.evaluationIntervalMs());

    try {
      if (status == Status.UNAVAILABLE && previous.status() != Status.UNAVAILABLE) {
        audit.record(
            AuditAction.TELEMETRY_UNAVAILABLE,
            AuditService.ACTOR_DETECTOR,
            "telemetry:prometheus",
            null,
            AuditResult.FAILURE,
            "No rule could be evaluated; health is unknown until telemetry returns. " + lastError);
      } else if (status != Status.UNAVAILABLE && previous.status() == Status.UNAVAILABLE) {
        audit.record(
            AuditAction.TELEMETRY_RESTORED,
            AuditService.ACTOR_DETECTOR,
            "telemetry:prometheus",
            null,
            AuditResult.SUCCESS,
            "Prometheus answered again; rule windows restart from fresh samples.");
      }
    } catch (RuntimeException e) {
      log.error("Could not record telemetry transition {} -> {}", previous.status(), status, e);
    }
  }

  public PipelineSnapshot pipeline() {
    return pipeline;
  }

  public List<RuleSnapshot> rules() {
    return catalog.all().stream().map(rule -> snapshots.get(rule.key())).toList();
  }
}
