package edu.cloudpulse.api.incident;

/** Why an evidence row was recorded. */
public enum EvidenceKind {
  /** The measurement that satisfied the sustain window and opened the incident. */
  BREACH_SUSTAINED,
  /** A periodic sample while the violation continues. */
  SAMPLE,
  /** A measurement that raised the incident's severity. */
  SEVERITY_ESCALATED,
  /** The measurement that completed the recovery window and resolved the incident. */
  RECOVERY_CONFIRMED
}
