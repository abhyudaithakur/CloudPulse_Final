import type { Dashboard, ServiceHealth } from '../api/types';
import { formatClock } from '../lib/format';
import { laneName, windowProgress } from '../lib/rules';

const HEADLINE: Record<ServiceHealth, string> = {
  STARTING: 'Starting up',
  HEALTHY: 'Healthy',
  DEGRADED: 'Degraded',
  UNAVAILABLE: 'Unavailable',
  UNKNOWN: 'Health unknown',
};

function list(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/**
 * One or two sentences explaining the headline (the worst service's health), built only from
 * what the API reported.
 */
export function explain(dashboard: Dashboard): string {
  const pipeline = dashboard.pipeline;
  const active = dashboard.incidents.filter((i) => i.status === 'ACTIVE');

  switch (dashboard.health) {
    case 'STARTING':
      return 'Waiting for the first evaluation cycle.';
    case 'UNKNOWN': {
      if (pipeline.status === 'UNAVAILABLE') {
        const since = pipeline.lastSuccessAt ? ` Last answer at ${formatClock(pipeline.lastSuccessAt)}.` : '';
        return `Prometheus is not answering, so no rule can be evaluated and nothing is assumed healthy.${since}`;
      }
      const blind = dashboard.rules.filter((r) => r.phase === 'NO_DATA' || r.phase === 'UNAVAILABLE');
      const byService = new Map<string, string[]>();
      for (const rule of blind) {
        byService.set(rule.serviceName, [...(byService.get(rule.serviceName) ?? []), laneName(rule.ruleId).toLowerCase()]);
      }
      const parts = [...byService].map(([service, rules]) => `${service} (${rules.join(', ')})`);
      return `No usable data for ${list(parts)}. Health can't be evaluated until it returns.`;
    }
    case 'UNAVAILABLE': {
      const down = dashboard.services.filter((s) => s.health === 'UNAVAILABLE').map((s) => s.name);
      return `Prometheus cannot scrape ${list(down)}. An availability incident is open.`;
    }
    case 'DEGRADED': {
      const names = active.map((i) => `${i.title.toLowerCase()} on ${i.serviceName} (${i.severity.toLowerCase()})`);
      const count = active.length === 1 ? '1 active incident' : `${active.length} active incidents`;
      const sources = [
        ...new Set(active.filter((i) => i.correlation === 'DEPENDENCY').map((i) => i.probableSource as string)),
      ];
      const source = sources.length ? ` Probable source: ${list(sources)}.` : '';
      return `${count}: ${names.join('; ')}.${source}`;
    }
    case 'HEALTHY': {
      const pending = dashboard.rules.find((r) => r.phase === 'PENDING');
      if (pending) {
        return `${laneName(pending.ruleId)} on ${pending.serviceName} is ${windowProgress(pending).text.toLowerCase()}. An incident opens if it holds.`;
      }
      const services = dashboard.services.length;
      return dashboard.rules.length === 1
        ? 'The rule is within threshold.'
        : `All ${dashboard.rules.length} rules on ${services === 1 ? '1 service' : `${services} services`} are within threshold.`;
    }
    default:
      return '';
  }
}

interface StatusBandProps {
  dashboard: Dashboard | undefined;
  apiError: string | undefined;
  updatedAt: number | undefined;
}

export function StatusBand({ dashboard, apiError, updatedAt }: StatusBandProps) {
  if (!dashboard) {
    return (
      <section className="status-band health-unknown" aria-live="polite">
        <p className="service-name">CloudPulse</p>
        <h1>{apiError ? 'Can’t reach the API' : 'Connecting…'}</h1>
        <p className="reason">
          {apiError
            ? 'Start the stack (docker compose up --build -d, or scripts/k8s-up), then this page refreshes on its own.'
            : 'Loading the latest evaluation.'}
        </p>
      </section>
    );
  }

  const health = dashboard.health ?? 'UNKNOWN';
  return (
    <section className={`status-band health-${health.toLowerCase()}`} aria-live="polite">
      <p className="service-name">
        {dashboard.services.length === 1
          ? dashboard.services[0].name
          : `${dashboard.services.length} services: ${dashboard.services.map((s) => s.name).join(', ')}`}
      </p>
      <h1>{HEADLINE[health]}</h1>
      <p className="reason">{explain(dashboard)}</p>
      <p className="evaluated">
        {apiError && updatedAt ? (
          <strong>
            API unreachable. Showing data received at {new Date(updatedAt).toLocaleTimeString()}.
          </strong>
        ) : (
          <>
            Last evaluation {formatClock(dashboard.pipeline.lastAttemptAt)}, every{' '}
            {Math.round(dashboard.pipeline.evaluationIntervalMs / 1000)} s
          </>
        )}
      </p>
    </section>
  );
}
