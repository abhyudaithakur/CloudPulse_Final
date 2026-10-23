package edu.cloudpulse.api.audit;

/**
 * State-changing operations that must leave an audit record (FR-13, NFR-05): incident creation,
 * status and severity changes, remediation approval, execution, result and verification, plus the
 * operator's fault injections and telemetry outages.
 */
public enum AuditAction {
  INCIDENT_OPENED,
  SEVERITY_ESCALATED,
  INCIDENT_RESOLVED,
  FAULT_INJECTED,
  FAULT_CLEARED,
  TELEMETRY_UNAVAILABLE,
  TELEMETRY_RESTORED,
  REMEDIATION_APPROVED,
  REMEDIATION_EXECUTED,
  REMEDIATION_ROLLED_OUT,
  REMEDIATION_VERIFIED,
  REMEDIATION_FAILED
}
