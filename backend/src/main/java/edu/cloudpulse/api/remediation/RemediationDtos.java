package edu.cloudpulse.api.remediation;

import java.time.Instant;

/** JSON shapes for the remediation API. */
public final class RemediationDtos {

  private RemediationDtos() {}

  /**
   * The body of an approval (FR-11). {@code confirm} must be {@code true} and {@code operator}
   * names the person approving: an approval is a deliberate, attributable act, never a default.
   */
  public record ApprovalRequest(String targetService, String operator, Boolean confirm, String note) {}

  /** A runbook that could be run for an incident, and whether it can be approved right now. */
  public record RunbookOption(
      String id,
      String title,
      String description,
      String targetService,
      String namespace,
      String deployment,
      boolean recommended,
      boolean available,
      String unavailableReason) {}

  public record RemediationView(
      long id,
      long incidentId,
      String runbook,
      String targetService,
      String target,
      String approvedBy,
      String approvalNote,
      RemediationStatus status,
      boolean inProgress,
      Instant approvedAt,
      Instant executedAt,
      Instant rolledOutAt,
      Instant verifyUntil,
      Instant finishedAt,
      String detail) {

    public static RemediationView from(RemediationAction a) {
      return new RemediationView(
          a.getId(),
          a.getIncidentId(),
          a.getRunbook().name(),
          a.getTargetService(),
          a.target(),
          a.getApprovedBy(),
          a.getApprovalNote(),
          a.getStatus(),
          a.getStatus().inProgress(),
          a.getApprovedAt(),
          a.getExecutedAt(),
          a.getRolledOutAt(),
          a.getVerifyUntil(),
          a.getFinishedAt(),
          a.getDetail());
    }
  }

  /** Whether this deployment can remediate at all (Kubernetes mode) and where. */
  public record RemediationConfig(
      boolean enabled,
      String mode,
      String namespace,
      long verificationWindowSeconds,
      String note) {}
}
