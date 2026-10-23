import { ArrowLeft } from 'lucide-react';
import { api } from '../api/client';
import type { AuditEvent, EvidenceItem, IncidentSummary } from '../api/types';
import { SeverityBadge, StatusBadge } from '../components/Badges';
import { ProbableSourcePanel } from '../components/ProbableSourcePanel';
import { RemediationPanel } from '../components/RemediationPanel';
import { hrefFor } from '../hooks/useHashRoute';
import { usePolling } from '../hooks/usePolling';
import {
  formatCondition,
  formatDateTime,
  formatDuration,
  formatValue,
  humanize,
  secondsBetween,
} from '../lib/format';
import { laneName } from '../lib/rules';

const EVIDENCE_KIND: Record<EvidenceItem['kind'], string> = {
  BREACH_SUSTAINED: 'Sustained breach',
  SAMPLE: 'Sample',
  SEVERITY_ESCALATED: 'Severity raised',
  RECOVERY_CONFIRMED: 'Recovery confirmed',
};

export function IncidentDetailPage({ id }: { id: number }) {
  const detail = usePolling((signal) => api.incident(id, signal), 3000, String(id));

  if (!detail.data) {
    const notFound = detail.error?.includes('not found');
    return (
      <section className="page">
        <BackLink />
        <h1>{notFound ? `Incident ${id} does not exist` : 'Loading incident…'}</h1>
        {detail.error && !notFound ? <p className="notice error">{detail.error}</p> : null}
      </section>
    );
  }

  const { incident, evidence, timeline, probableSource, runbooks, remediations } = detail.data;
  return (
    <article className="page incident-detail" aria-labelledby="incident-title">
      <BackLink />
      <header className="incident-head">
        <p className="incident-key">{incident.key}</p>
        <h1 id="incident-title">{incident.title}</h1>
        <div className="incident-badges">
          <StatusBadge status={incident.status} />
          <SeverityBadge severity={incident.severity} />
        </div>
        <p className="incident-summary">{incident.summary}</p>
      </header>

      {detail.stale ? (
        <p className="notice">API unreachable. Showing this incident as last received.</p>
      ) : null}

      <Facts incident={incident} />

      {probableSource ? <ProbableSourcePanel source={probableSource} /> : null}

      <RemediationPanel
        incidentId={incident.id}
        runbooks={runbooks}
        remediations={remediations ?? []}
        onChanged={detail.refresh}
      />

      <div className="detail-columns">
        <section aria-labelledby="evidence-title">
          <h2 id="evidence-title">Evidence</h2>
          <EvidenceList items={evidence} />
        </section>
        <section aria-labelledby="timeline-title">
          <h2 id="timeline-title">Timeline</h2>
          <Timeline events={timeline} />
        </section>
      </div>
    </article>
  );
}

function BackLink() {
  return (
    <a className="back" href={hrefFor({ page: 'incidents' })}>
      <ArrowLeft aria-hidden size={16} /> All incidents
    </a>
  );
}

function Facts({ incident }: { incident: IncidentSummary }) {
  const facts: [string, string][] = [
    ['Service', incident.serviceName],
    ['Rule', `${laneName(incident.ruleId)} (${incident.ruleId})`],
    ['Condition', formatCondition(incident.threshold, incident.unit, incident.comparison, incident.sustainSeconds)],
    ['Breach began', formatDateTime(incident.breachStartedAt)],
    ['Opened', `${formatDateTime(incident.openedAt)}, ${incident.detectionDelaySeconds.toFixed(1)} s after the window filled`],
    ['Resolved', incident.resolvedAt ? formatDateTime(incident.resolvedAt) : 'Still active'],
    ['Duration', formatDuration(secondsBetween(incident.openedAt, incident.resolvedAt))],
    ['Peak', formatValue(incident.peakValue, incident.unit)],
    ['Latest', `${formatValue(incident.lastValue, incident.unit)} at ${formatDateTime(incident.lastObservedAt)}`],
  ];
  return (
    <dl className="facts">
      {facts.map(([term, value]) => (
        <div key={term}>
          <dt>{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function EvidenceList({ items }: { items: EvidenceItem[] }) {
  if (items.length === 0) return <p>No evidence recorded.</p>;
  return (
    <ol className="evidence">
      {items.map((item) => (
        <li key={item.id} className={`evidence-${item.kind.toLowerCase()}`}>
          <p className="evidence-head">
            <span className="evidence-kind">{EVIDENCE_KIND[item.kind]}</span>
            <span className="num">{formatValue(item.observedValue, item.unit)}</span>
            <span className="muted">
              vs {formatValue(item.threshold, item.unit)}, {formatDateTime(item.observedAt)}
            </span>
          </p>
          <p>{item.note}</p>
          <details>
            <summary>Query ({item.source})</summary>
            <code>{item.promql}</code>
          </details>
        </li>
      ))}
    </ol>
  );
}

function Timeline({ events }: { events: AuditEvent[] }) {
  if (events.length === 0) return <p>No audit events recorded.</p>;
  return (
    <ol className="timeline">
      {events.map((event) => (
        <li key={event.id} className={event.result === 'FAILURE' ? 'failed' : undefined}>
          <p className="timeline-head">
            <span className="timeline-action">{humanize(event.action)}</span>
            <span className="muted">
              {formatDateTime(event.occurredAt)}, by {event.actor}
            </span>
          </p>
          <p>{event.details}</p>
        </li>
      ))}
    </ol>
  );
}
