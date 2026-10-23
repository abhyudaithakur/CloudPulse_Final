package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.audit.AuditAction;
import edu.cloudpulse.api.audit.AuditResult;
import edu.cloudpulse.api.audit.AuditService;
import edu.cloudpulse.api.config.CloudPulseProperties;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.Severity;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Incident module: lifecycle, evidence and deduplication. Each public mutator is one transaction
 * that changes incident state, appends evidence and appends the matching audit event together.
 */
@Service
public class IncidentService {

  private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

  private final IncidentRepository incidents;
  private final EvidenceRepository evidence;
  private final AuditService audit;
  private final MeterRegistry meters;
  private final Duration evidenceSampleInterval;

  public IncidentService(
      IncidentRepository incidents,
      EvidenceRepository evidence,
      AuditService audit,
      MeterRegistry meters,
      CloudPulseProperties properties) {
    this.incidents = incidents;
    this.evidence = evidence;
    this.audit = audit;
    this.meters = meters;
    this.evidenceSampleInterval = properties.detection().evidenceSampleInterval();
    Gauge.builder(
            "cloudpulse.incidents.active", incidents, r -> r.countByStatus(IncidentStatus.ACTIVE))
        .description("Incidents currently ACTIVE")
        .strongReference(true)
        .register(meters);
  }

  @Transactional(readOnly = true)
  public boolean hasActive(RuleDefinition rule) {
    return incidents.existsByRuleIdAndServiceNameAndStatus(
        rule.id(), rule.serviceName(), IncidentStatus.ACTIVE);
  }

  /**
   * Opens an incident for a sustained violation (FR-03). Idempotent: if one is already active for
   * the same rule and service, the sample is added to it instead (FR-04).
   */
  @Transactional
  public Incident open(RuleDefinition rule, Instant breachStartedAt, Instant now, double value) {
    Optional<Incident> existing = findActive(rule);
    if (existing.isPresent()) {
      observe(existing.get(), rule, now, value);
      return existing.get();
    }

    long sustained = Duration.between(breachStartedAt, now).toSeconds();
    String summary =
        String.format(
            "%s: %s held for %d s (sustain window %d s).",
            rule.title(), rule.describe(value), sustained, rule.sustain().toSeconds());
    Incident incident = incidents.save(Incident.open(rule, breachStartedAt, now, value, summary));

    evidence.save(
        Evidence.of(
            incident,
            rule,
            now,
            EvidenceKind.BREACH_SUSTAINED,
            value,
            String.format(
                "Every sample for %d s was past the threshold (sustain window %d s).",
                sustained, rule.sustain().toSeconds())));
    audit.record(
        AuditAction.INCIDENT_OPENED,
        AuditService.ACTOR_DETECTOR,
        incident.auditTarget(),
        incident.getId(),
        AuditResult.SUCCESS,
        String.format(
            "Opened %s incident for %s on %s: %s.",
            incident.getSeverity(), rule.id(), rule.serviceName(), rule.describe(value)));

    meters
        .counter(
            "cloudpulse.incidents.opened",
            "rule",
            rule.id(),
            "severity",
            incident.getSeverity().name())
        .increment();
    DistributionSummary.builder("cloudpulse.detection.delay")
        .description("Seconds between sustain-window satisfaction and incident creation (NFR-01)")
        .baseUnit("seconds")
        .tag("rule", rule.id())
        .register(meters)
        .record(incident.detectionDelaySeconds());
    log.info("Opened {} ({}) for rule {}", incident.key(), incident.getSeverity(), rule.id());
    return incident;
  }

  /** Adds a violating sample to the active incident: escalation evidence or a periodic sample. */
  @Transactional
  public void recordBreach(RuleDefinition rule, Instant now, double value) {
    findActive(rule).ifPresent(incident -> observe(incident, rule, now, value));
  }

  /** Resolves the active incident once the recovery window is satisfied (UC-05). */
  @Transactional
  public void resolve(RuleDefinition rule, Instant now, double value, Instant recoveryStartedAt) {
    findActive(rule)
        .ifPresent(
            incident -> {
              long healthyFor =
                  recoveryStartedAt == null
                      ? rule.recovery().toSeconds()
                      : Duration.between(recoveryStartedAt, now).toSeconds();
              incident.resolve(now, value);
              evidence.save(
                  Evidence.of(
                      incident,
                      rule,
                      now,
                      EvidenceKind.RECOVERY_CONFIRMED,
                      value,
                      String.format(
                          "Back within threshold (%s) for %d s; recovery window %d s satisfied.",
                          rule.unit().format(value), healthyFor, rule.recovery().toSeconds())));
              audit.record(
                  AuditAction.INCIDENT_RESOLVED,
                  AuditService.ACTOR_DETECTOR,
                  incident.auditTarget(),
                  incident.getId(),
                  AuditResult.SUCCESS,
                  String.format(
                      "Resolved after %s; condition clear for %d s.",
                      humanDuration(Duration.between(incident.getOpenedAt(), now)),
                      healthyFor));
              meters.counter("cloudpulse.incidents.resolved", "rule", rule.id()).increment();
              log.info("Resolved {} for rule {}", incident.key(), rule.id());
            });
  }

  private void observe(Incident incident, RuleDefinition rule, Instant now, double value) {
    Severity before = incident.getSeverity();
    boolean escalated = incident.observe(now, value, rule.severityFor(value));
    if (escalated) {
      evidence.save(
          Evidence.of(
              incident,
              rule,
              now,
              EvidenceKind.SEVERITY_ESCALATED,
              value,
              String.format(
                  "Severity %s → %s at %s.", before, incident.getSeverity(), rule.describe(value))));
      audit.record(
          AuditAction.SEVERITY_ESCALATED,
          AuditService.ACTOR_DETECTOR,
          incident.auditTarget(),
          incident.getId(),
          AuditResult.SUCCESS,
          String.format(
              "Severity escalated %s → %s (%s).",
              before, incident.getSeverity(), rule.unit().format(value)));
      incident.markEvidence(now);
    } else if (incident.evidenceDue(now, evidenceSampleInterval)) {
      evidence.save(
          Evidence.of(
              incident,
              rule,
              now,
              EvidenceKind.SAMPLE,
              value,
              "Violation continues: " + rule.describe(value) + "."));
      incident.markEvidence(now);
    }
  }

  private Optional<Incident> findActive(RuleDefinition rule) {
    return incidents.findFirstByRuleIdAndServiceNameAndStatus(
        rule.id(), rule.serviceName(), IncidentStatus.ACTIVE);
  }

  // ---- queries -----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<Incident> search(IncidentStatus status, String service, String ruleId, int limit) {
    Specification<Incident> filter =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();
          if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
          }
          if (service != null && !service.isBlank()) {
            predicates.add(cb.equal(root.get("serviceName"), service));
          }
          if (ruleId != null && !ruleId.isBlank()) {
            predicates.add(cb.equal(root.get("ruleId"), ruleId));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };
    return incidents
        .findAll(filter, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "openedAt", "id")))
        .getContent();
  }

  /** Incidents on the given services that were open at any time since {@code since}. */
  @Transactional(readOnly = true)
  public List<Incident> touching(java.util.Collection<String> services, Instant since) {
    return services.isEmpty() ? List.of() : incidents.findTouching(services, since);
  }

  @Transactional(readOnly = true)
  public Optional<Incident> find(long id) {
    return incidents.findById(id);
  }

  @Transactional(readOnly = true)
  public List<Evidence> evidenceFor(long incidentId) {
    return evidence.findByIncidentIdOrderByObservedAtAscIdAsc(incidentId);
  }

  @Transactional(readOnly = true)
  public List<Incident> active() {
    return search(IncidentStatus.ACTIVE, null, null, 200);
  }

  @Transactional(readOnly = true)
  public long countActive() {
    return incidents.countByStatus(IncidentStatus.ACTIVE);
  }

  @Transactional(readOnly = true)
  public long countAll() {
    return incidents.count();
  }

  static String humanDuration(Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
  }
}
