import type { IncidentStatus, Severity } from '../api/types';

const SEVERITY_LABEL: Record<Severity, string> = {
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <span className={`badge severity severity-${severity.toLowerCase()}`}>
      {SEVERITY_LABEL[severity]}
    </span>
  );
}

export function StatusBadge({ status }: { status: IncidentStatus }) {
  return status === 'ACTIVE' ? (
    <span className="badge status status-active">Active</span>
  ) : (
    <span className="badge status status-resolved">Resolved</span>
  );
}
