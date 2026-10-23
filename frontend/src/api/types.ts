// JSON contracts of the CloudPulse API. Mirrors the backend DTO records.

export type Severity = 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type IncidentStatus = 'ACTIVE' | 'RESOLVED';
export type MetricUnit = 'SECONDS' | 'RATIO' | 'BOOLEAN';
export type Comparison = 'ABOVE' | 'BELOW';
export type RulePhase =
  | 'STARTING'
  | 'HEALTHY'
  | 'PENDING'
  | 'BREACHING'
  | 'RECOVERING'
  | 'NO_DATA'
  | 'UNAVAILABLE';
export type PipelineStatus = 'STARTING' | 'CONNECTED' | 'DEGRADED' | 'UNAVAILABLE';
export type ServiceHealth = 'STARTING' | 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE' | 'UNKNOWN';
export type TelemetryStatus = 'OK' | 'NO_DATA' | 'UNAVAILABLE';

export interface IncidentSummary {
  id: number;
  key: string;
  ruleId: string;
  serviceName: string;
  title: string;
  status: IncidentStatus;
  severity: Severity;
  unit: MetricUnit;
  comparison: Comparison;
  threshold: number;
  lastValue: number;
  peakValue: number;
  sustainSeconds: number;
  recoverySeconds: number;
  breachStartedAt: string;
  openedAt: string;
  resolvedAt: string | null;
  lastObservedAt: string;
  detectionDelaySeconds: number;
  summary: string;
  /** Service named as the probable source (FR-08); null when inconclusive. */
  probableSource: string | null;
  correlation: Verdict | null;
}

export type Verdict = 'DEPENDENCY' | 'SELF' | 'INCONCLUSIVE';

export interface RelatedIncident {
  id: number;
  key: string;
  serviceName: string;
  ruleId: string;
  title: string;
  /** "dependency": a service this one calls. "dependent": a service that calls this one. */
  relation: 'dependency' | 'dependent';
  status: IncidentStatus;
  severity: Severity;
  breachStartedAt: string;
}

export interface ProbableSource {
  verdict: Verdict;
  service: string | null;
  statement: string;
  evidence: string[];
  related: RelatedIncident[];
}

export type EvidenceKind = 'BREACH_SUSTAINED' | 'SAMPLE' | 'SEVERITY_ESCALATED' | 'RECOVERY_CONFIRMED';

export interface EvidenceItem {
  id: number;
  observedAt: string;
  kind: EvidenceKind;
  metric: string;
  observedValue: number;
  threshold: number;
  unit: MetricUnit;
  source: string;
  promql: string;
  note: string;
}

export interface AuditEvent {
  id: number;
  occurredAt: string;
  action: string;
  actor: string;
  target: string;
  incidentId: number | null;
  result: 'SUCCESS' | 'FAILURE';
  details: string;
}

export interface RunbookOption {
  id: string;
  title: string;
  description: string;
  targetService: string;
  namespace: string;
  deployment: string;
  recommended: boolean;
  available: boolean;
  unavailableReason: string | null;
}

export type RemediationStatus =
  | 'APPROVED'
  | 'EXECUTING'
  | 'ROLLING_OUT'
  | 'VERIFYING'
  | 'RECOVERED'
  | 'NOT_RECOVERED'
  | 'FAILED';

export interface Remediation {
  id: number;
  incidentId: number;
  runbook: string;
  targetService: string;
  /** "deployment:namespace/name", as in the audit trail. */
  target: string;
  approvedBy: string;
  approvalNote: string;
  status: RemediationStatus;
  inProgress: boolean;
  approvedAt: string;
  executedAt: string | null;
  rolledOutAt: string | null;
  verifyUntil: string | null;
  finishedAt: string | null;
  detail: string;
}

export interface RemediationConfig {
  enabled: boolean;
  mode: 'kubernetes' | 'disabled';
  namespace: string;
  verificationWindowSeconds: number;
  note: string;
}

export interface ApprovalRequest {
  targetService: string;
  operator: string;
  confirm: boolean;
  note?: string;
}

export interface IncidentDetail {
  incident: IncidentSummary;
  evidence: EvidenceItem[];
  timeline: AuditEvent[];
  probableSource: ProbableSource;
  runbooks: RunbookOption[];
  remediations: Remediation[];
}

export interface RuleSnapshot {
  /** serviceName/ruleId: unique across services. */
  key: string;
  ruleId: string;
  serviceName: string;
  title: string;
  unit: MetricUnit;
  comparison: Comparison;
  threshold: number;
  sustainSeconds: number;
  recoverySeconds: number;
  phase: RulePhase;
  value: number | null;
  telemetryStatus: TelemetryStatus | null;
  detail: string;
  evaluatedAt: string | null;
  breachStartedAt: string | null;
  recoveryStartedAt: string | null;
}

export interface PipelineSnapshot {
  status: PipelineStatus;
  source: string;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  lastError: string;
  failingQueries: number;
  totalQueries: number;
  evaluationIntervalMs: number;
}

export interface ServiceView {
  name: string;
  health: ServiceHealth;
  activeIncidents: number;
  ruleIds: string[];
  /** Services this one calls (the dependency map behind FR-08). */
  dependsOn: string[];
  deployment: string;
}

export interface FaultState {
  service: string;
  mode: string;
  reachable: boolean;
  /** Stuck: the fault cannot be cleared through the API, only by a restart (FR-09). */
  needsRestart: boolean;
}

export interface Dashboard {
  generatedAt: string;
  /** Worst health across services. */
  health: ServiceHealth;
  pipeline: PipelineSnapshot;
  services: ServiceView[];
  rules: RuleSnapshot[];
  counts: { active: number; total: number };
  faults: FaultState[];
  incidents: IncidentSummary[];
  remediation: RemediationConfig;
  prometheusUrl: string;
}

export interface SeriesPoint {
  t: string;
  v: number | null;
}

export interface Series {
  service: string;
  ruleId: string;
  unit: MetricUnit;
  threshold: number;
  start: string;
  end: string;
  stepSeconds: number;
  status: 'OK' | 'UNAVAILABLE';
  detail: string;
  points: SeriesPoint[];
}
