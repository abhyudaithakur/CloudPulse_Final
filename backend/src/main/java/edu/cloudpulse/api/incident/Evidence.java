package edu.cloudpulse.api.incident;

import edu.cloudpulse.api.domain.MetricUnit;
import edu.cloudpulse.api.domain.RuleDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One measurement supporting an incident (FR-05): the metric/rule, observed value, threshold,
 * source and timestamp, plus the exact PromQL so anyone can re-run it in Prometheus.
 */
@Entity
@Table(name = "incident_evidence")
public class Evidence {

  public static final String SOURCE_PROMETHEUS = "prometheus";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long incidentId;

  @Column(nullable = false)
  private Instant observedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private EvidenceKind kind;

  @Column(nullable = false, length = 64)
  private String metric;

  @Column(nullable = false)
  private double observedValue;

  @Column(nullable = false)
  private double thresholdValue;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MetricUnit unit;

  @Column(nullable = false, length = 32)
  private String source;

  @Column(nullable = false, length = 2000)
  private String promql;

  @Column(nullable = false, length = 500)
  private String note;

  protected Evidence() {}

  private Evidence(
      Long incidentId,
      RuleDefinition rule,
      Instant observedAt,
      EvidenceKind kind,
      double observedValue,
      String note) {
    this.incidentId = incidentId;
    this.observedAt = observedAt;
    this.kind = kind;
    this.metric = rule.id();
    this.observedValue = observedValue;
    this.thresholdValue = rule.threshold();
    this.unit = rule.unit();
    this.source = SOURCE_PROMETHEUS;
    this.promql = rule.query();
    this.note = note.length() > 500 ? note.substring(0, 500) : note;
  }

  static Evidence of(
      Incident incident, RuleDefinition rule, Instant observedAt, EvidenceKind kind, double value,
      String note) {
    return new Evidence(incident.getId(), rule, observedAt, kind, value, note);
  }

  public Long getId() {
    return id;
  }

  public Long getIncidentId() {
    return incidentId;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public EvidenceKind getKind() {
    return kind;
  }

  public String getMetric() {
    return metric;
  }

  public double getObservedValue() {
    return observedValue;
  }

  public double getThresholdValue() {
    return thresholdValue;
  }

  public MetricUnit getUnit() {
    return unit;
  }

  public String getSource() {
    return source;
  }

  public String getPromql() {
    return promql;
  }

  public String getNote() {
    return note;
  }
}
