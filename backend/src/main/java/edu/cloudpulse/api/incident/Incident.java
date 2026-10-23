package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.domain.Comparison;
import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.RuleDefinition;
import edu.cloudpulse.api.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;

/**
 * A persisted operational condition (FR-03): affected service, incident type (rule), severity,
 * status and timestamps. The rule's threshold and windows are copied in at open time so the
 * record still explains itself if the configuration changes later.
 *
 * <p>At most one ACTIVE incident per (rule, service) is enforced by a partial unique index in the
 * database (V1 migration), not only by application logic (FR-04).
 */
@Entity
@Table(name = "incidents")
public class Incident {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String ruleId;

  @Column(nullable = false, length = 64)
  private String serviceName;

  @Column(nullable = false, length = 200)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private IncidentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Severity severity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MetricUnit unit;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private Comparison comparison;

  @Column(nullable = false)
  private double thresholdValue;

  @Column(nullable = false)
  private long sustainSeconds;

  @Column(nullable = false)
  private long recoverySeconds;

  @Column(nullable = false)
  private Instant breachStartedAt;

  @Column(nullable = false)
  private Instant openedAt;

  private Instant resolvedAt;

  @Column(nullable = false)
  private double lastValue;

  @Column(nullable = false)
  private Instant lastObservedAt;

  @Column(nullable = false)
  private double peakValue;

  @Column(nullable = false)
  private Instant lastEvidenceAt;

  @Column(nullable = false, length = 1000)
  private String summary;

  @Version private long version;

  protected Incident() {}

  private Incident(
      RuleDefinition rule, Instant breachStartedAt, Instant now, double value, String summary) {
    this.ruleId = rule.id();
    this.serviceName = rule.serviceName();
    this.title = rule.title();
    this.status = IncidentStatus.ACTIVE;
    this.severity = rule.severityFor(value);
    this.unit = rule.unit();
    this.comparison = rule.comparison();
    this.thresholdValue = rule.threshold();
    this.sustainSeconds = rule.sustain().toSeconds();
    this.recoverySeconds = rule.recovery().toSeconds();
    this.breachStartedAt = breachStartedAt;
    this.openedAt = now;
    this.lastValue = value;
    this.lastObservedAt = now;
    this.peakValue = value;
    this.lastEvidenceAt = now;
    this.summary = summary;
  }

  static Incident open(
      RuleDefinition rule, Instant breachStartedAt, Instant now, double value, String summary) {
    return new Incident(rule, breachStartedAt, now, value, summary);
  }

  /**
   * Records a new violating sample. Severity only ever escalates during an incident: it reflects
   * the worst condition seen, so a brief dip cannot quietly downgrade an incident.
   *
   * @return true if the severity was escalated by this sample
   */
  boolean observe(Instant at, double value, Severity candidate) {
    this.lastValue = value;
    this.lastObservedAt = at;
    if (comparison.isWorse(value, peakValue)) {
      this.peakValue = value;
    }
    if (candidate.isHigherThan(severity)) {
      this.severity = candidate;
      return true;
    }
    return false;
  }

  boolean evidenceDue(Instant now, Duration interval) {
    return Duration.between(lastEvidenceAt, now).compareTo(interval) >= 0;
  }

  void markEvidence(Instant at) {
    this.lastEvidenceAt = at;
  }

  void resolve(Instant at, double value) {
    this.status = IncidentStatus.RESOLVED;
    this.resolvedAt = at;
    this.lastValue = value;
    this.lastObservedAt = at;
  }

  /** When the sustain window was satisfied: breach start + sustain. */
  public Instant windowSatisfiedAt() {
    return breachStartedAt.plusSeconds(sustainSeconds);
  }

  /** Time from window satisfaction to incident creation; NFR-01 requires ≤ 30 s. */
  public double detectionDelaySeconds() {
    long millis = Duration.between(windowSatisfiedAt(), openedAt).toMillis();
    return Math.max(0, millis) / 1000.0;
  }

  public String key() {
    return id == null ? "CP-NEW" : String.format("CP-%04d", id);
  }

  public String auditTarget() {
    return "incident:" + key();
  }

  public boolean isActive() {
    return status == IncidentStatus.ACTIVE;
  }

  public Long getId() {
    return id;
  }

  public String getRuleId() {
    return ruleId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getTitle() {
    return title;
  }

  public IncidentStatus getStatus() {
    return status;
  }

  public Severity getSeverity() {
    return severity;
  }

  public MetricUnit getUnit() {
    return unit;
  }

  public Comparison getComparison() {
    return comparison;
  }

  public double getThresholdValue() {
    return thresholdValue;
  }

  public long getSustainSeconds() {
    return sustainSeconds;
  }

  public long getRecoverySeconds() {
    return recoverySeconds;
  }

  public Instant getBreachStartedAt() {
    return breachStartedAt;
  }

  public Instant getOpenedAt() {
    return openedAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public double getLastValue() {
    return lastValue;
  }

  public Instant getLastObservedAt() {
    return lastObservedAt;
  }

  public double getPeakValue() {
    return peakValue;
  }

  public Instant getLastEvidenceAt() {
    return lastEvidenceAt;
  }

  public String getSummary() {
    return summary;
  }
}
