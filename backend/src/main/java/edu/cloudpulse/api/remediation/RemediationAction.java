package edu.cloudpulse.api.remediation;

import edu.cloudpulse.api.domain.MonitoredService;
import edu.cloudpulse.api.incident.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * One approved remediation and how far it got. Persisted at every step, so after an API restart
 * the work resumes from the database and can never be executed twice without a new approval.
 * At most one per incident can be in progress (partial unique index, V2 migration).
 */
@Entity
@Table(name = "remediation_actions")
public class RemediationAction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long incidentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 48)
  private Runbook runbook;

  @Column(nullable = false, length = 64)
  private String targetService;

  @Column(nullable = false, length = 64)
  private String namespace;

  @Column(nullable = false, length = 64)
  private String deployment;

  @Column(nullable = false, length = 64)
  private String approvedBy;

  @Column(nullable = false, length = 500)
  private String approvalNote;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RemediationStatus status;

  @Column(nullable = false)
  private Instant approvedAt;

  private Instant executedAt;
  private Long generation;
  private Instant rolledOutAt;
  private Instant verifyUntil;
  private Instant finishedAt;

  @Column(nullable = false, length = 1000)
  private String detail;

  @Version private long version;

  protected RemediationAction() {}

  static RemediationAction approved(
      Incident incident,
      Runbook runbook,
      MonitoredService target,
      String namespace,
      String operator,
      String note,
      Instant at) {
    RemediationAction action = new RemediationAction();
    action.incidentId = incident.getId();
    action.runbook = runbook;
    action.targetService = target.name();
    action.namespace = namespace;
    action.deployment = target.deployment();
    action.approvedBy = operator;
    action.approvalNote = note == null ? "" : truncate(note, 500);
    action.status = RemediationStatus.APPROVED;
    action.approvedAt = at;
    action.detail = "Approved; waiting to execute.";
    return action;
  }

  void executing(Instant at) {
    status = RemediationStatus.EXECUTING;
    executedAt = at;
    detail = "Sending the restart to Kubernetes.";
  }

  void rollingOut(long newGeneration, String text) {
    status = RemediationStatus.ROLLING_OUT;
    generation = newGeneration;
    detail = truncate(text, 1000);
  }

  void verifying(Instant at, Instant until, String text) {
    status = RemediationStatus.VERIFYING;
    rolledOutAt = at;
    verifyUntil = until;
    detail = truncate(text, 1000);
  }

  void finish(RemediationStatus outcome, Instant at, String text) {
    status = outcome;
    finishedAt = at;
    detail = truncate(text, 1000);
  }

  public String target() {
    return "deployment:" + namespace + "/" + deployment;
  }

  private static String truncate(String text, int max) {
    return text.length() > max ? text.substring(0, max) : text;
  }

  public Long getId() {
    return id;
  }

  public Long getIncidentId() {
    return incidentId;
  }

  public Runbook getRunbook() {
    return runbook;
  }

  public String getTargetService() {
    return targetService;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getDeployment() {
    return deployment;
  }

  public String getApprovedBy() {
    return approvedBy;
  }

  public String getApprovalNote() {
    return approvalNote;
  }

  public RemediationStatus getStatus() {
    return status;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public Instant getExecutedAt() {
    return executedAt;
  }

  public Long getGeneration() {
    return generation;
  }

  public Instant getRolledOutAt() {
    return rolledOutAt;
  }

  public Instant getVerifyUntil() {
    return verifyUntil;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public String getDetail() {
    return detail;
  }
}
