package edu.cloudpulse.api.remediation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one approved remediation:
 *
 * <pre>
 * APPROVED → EXECUTING → ROLLING_OUT → VERIFYING → RECOVERED
 *                │             │            └───→ NOT_RECOVERED   (UC-05 alternative flow)
 *                └─────────────┴────────────────→ FAILED          (UC-04 alternative flow)
 * </pre>
 */
public enum RemediationStatus {
  /** An operator approved it; nothing has changed in the cluster yet. */
  APPROVED,
  /** The restart request is being sent to Kubernetes. */
  EXECUTING,
  /** Kubernetes accepted the restart and is replacing pods. */
  ROLLING_OUT,
  /** The rollout finished; waiting for the incident to resolve within the verification window. */
  VERIFYING,
  /** The incident resolved within the verification window (FR-12). */
  RECOVERED,
  /** The rollout finished but the condition persisted; the incident stays active. */
  NOT_RECOVERED,
  /** Kubernetes rejected the change, the rollout timed out, or the attempt was interrupted. */
  FAILED;

  public static final Set<RemediationStatus> IN_PROGRESS =
      EnumSet.of(APPROVED, EXECUTING, ROLLING_OUT, VERIFYING);

  public boolean inProgress() {
    return IN_PROGRESS.contains(this);
  }
}
