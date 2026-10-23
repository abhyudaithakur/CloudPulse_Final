package edu.cloudpulse.api.incident;

/**
 * Incident lifecycle. The Kubernetes remediation phase will add REMEDIATING and
 * RECOVERY_VERIFYING between these two (see docs/roadmap).
 */
public enum IncidentStatus {
  ACTIVE,
  RESOLVED
}
