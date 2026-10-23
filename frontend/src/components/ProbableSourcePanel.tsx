import type { ProbableSource } from '../api/types';
import { hrefFor } from '../hooks/useHashRoute';
import { formatClock } from '../lib/format';
import { StatusBadge } from './Badges';

const VERDICT: Record<ProbableSource['verdict'], { label: string; tone: string }> = {
  DEPENDENCY: { label: 'A dependency', tone: 'stop' },
  SELF: { label: 'This service', tone: 'caution' },
  INCONCLUSIVE: { label: 'Inconclusive', tone: 'unknown' },
};

/**
 * Where the incident probably started (FR-08, UC-03): the verdict, the reasoning, and links to
 * the related incidents. Worded as "probable" on purpose: it is inferred from the dependency map
 * and timing, not proven.
 */
export function ProbableSourcePanel({ source }: { source: ProbableSource }) {
  const verdict = VERDICT[source.verdict];
  return (
    <section className={`probable-source tone-${verdict.tone}`} aria-labelledby="source-title">
      <div className="source-head">
        <h2 id="source-title">Probable source</h2>
        <span className="source-verdict">
          {verdict.label}
          {source.service ? <strong>{source.service}</strong> : null}
        </span>
      </div>
      <p className="source-statement">{source.statement}</p>
      {source.evidence.length ? (
        <ul className="source-evidence">
          {source.evidence.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      ) : null}
      {source.related.length ? (
        <div className="source-related">
          <h3>Related incidents</h3>
          <ul>
            {source.related.map((r) => (
              <li key={r.id}>
                <a href={hrefFor({ page: 'incident', id: r.id })}>
                  <span className="incident-key">{r.key}</span> {r.title} on {r.serviceName}
                </a>
                <span className="muted">
                  {r.relation === 'dependency' ? 'a service this one calls' : 'a service that calls this one'}, began{' '}
                  {formatClock(r.breachStartedAt)}
                </span>
                <StatusBadge status={r.status} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
