import type { IncidentSummary } from '../api/types';
import { hrefFor } from '../hooks/useHashRoute';
import { formatClock, formatDateTime, formatDuration, formatValue, secondsBetween } from '../lib/format';
import { laneName } from '../lib/rules';
import { SeverityBadge, StatusBadge } from './Badges';

export const INCIDENT_COLUMNS = ['Incident', 'Service', 'Rule', 'Status', 'Severity', 'Opened', 'Duration', 'Peak'] as const;

interface IncidentTableProps {
  incidents: IncidentSummary[];
  /** Overview shows today's times only; the full list shows dates. */
  compact?: boolean;
  now?: number;
}

export function IncidentTable({ incidents, compact = false, now = Date.now() }: IncidentTableProps) {
  if (incidents.length === 0) {
    return (
      <div className="empty">
        <p>
          <strong>No incidents yet.</strong> Inject a fault to watch detection, evidence and
          recovery end to end.
        </p>
      </div>
    );
  }

  return (
    <div className="table-scroll">
      <table className="incidents-table">
        <thead>
          <tr>
            {INCIDENT_COLUMNS.map((column) => (
              <th key={column} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {incidents.map((incident) => (
            <tr key={incident.id} className={incident.status === 'ACTIVE' ? 'row-active' : undefined}>
              <td>
                <a className="incident-link" href={hrefFor({ page: 'incident', id: incident.id })}>
                  <span className="incident-key">{incident.key}</span>
                  <span className="incident-title">{incident.title}</span>
                </a>
              </td>
              <td>
                <span className="service-cell">{incident.serviceName}</span>
                {incident.correlation === 'DEPENDENCY' && incident.probableSource ? (
                  <span className="via">probable source {incident.probableSource}</span>
                ) : null}
              </td>
              <td>{laneName(incident.ruleId)}</td>
              <td>
                <StatusBadge status={incident.status} />
              </td>
              <td>
                <SeverityBadge severity={incident.severity} />
              </td>
              <td className="num">{compact ? formatClock(incident.openedAt) : formatDateTime(incident.openedAt)}</td>
              <td className="num">{formatDuration(secondsBetween(incident.openedAt, incident.resolvedAt, now))}</td>
              <td className="num">{formatValue(incident.peakValue, incident.unit)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
