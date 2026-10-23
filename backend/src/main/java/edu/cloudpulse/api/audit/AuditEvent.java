package edu.cloudpulse.api.audit;

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
 * Append-only audit record: timestamp, action, target and result (NFR-05). The entity has no
 * mutators, the repository exposes no update or delete, and a database trigger rejects UPDATE and
 * DELETE on the table (V1 migration).
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Instant occurredAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 48)
  private AuditAction action;

  @Column(nullable = false, length = 64)
  private String actor;

  @Column(nullable = false, length = 128)
  private String target;

  private Long incidentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AuditResult result;

  @Column(nullable = false, length = 1000)
  private String details;

  protected AuditEvent() {}

  AuditEvent(
      Instant occurredAt,
      AuditAction action,
      String actor,
      String target,
      Long incidentId,
      AuditResult result,
      String details) {
    this.occurredAt = occurredAt;
    this.action = action;
    this.actor = actor;
    this.target = target;
    this.incidentId = incidentId;
    this.result = result;
    String text = details == null ? "" : details;
    this.details = text.length() > 1000 ? text.substring(0, 1000) : text;
  }

  public Long getId() {
    return id;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public AuditAction getAction() {
    return action;
  }

  public String getActor() {
    return actor;
  }

  public String getTarget() {
    return target;
  }

  public Long getIncidentId() {
    return incidentId;
  }

  public AuditResult getResult() {
    return result;
  }

  public String getDetails() {
    return details;
  }
}
