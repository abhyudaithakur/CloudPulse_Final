import { Activity, ExternalLink } from 'lucide-react';
import type { PipelineSnapshot } from '../api/types';
import { hrefFor, type Route } from '../hooks/useHashRoute';

interface HeaderProps {
  route: Route;
  activeIncidents: number | undefined;
  pipeline: PipelineSnapshot | undefined;
  apiReachable: boolean;
  /** Where the browser can open Prometheus (differs between Docker Compose and Kubernetes). */
  prometheusUrl: string | undefined;
}

const PIPELINE_TEXT: Record<PipelineSnapshot['status'], string> = {
  STARTING: 'Prometheus: starting',
  CONNECTED: 'Prometheus connected',
  DEGRADED: 'Prometheus: some queries failing',
  UNAVAILABLE: 'Prometheus unavailable',
};

export function Header({ route, activeIncidents, pipeline, apiReachable, prometheusUrl }: HeaderProps) {
  const current = route.page === 'incident' ? 'incidents' : route.page;
  const tabs = [
    { page: 'overview', label: 'Overview', href: hrefFor({ page: 'overview' }) },
    { page: 'incidents', label: 'Incidents', href: hrefFor({ page: 'incidents' }) },
    { page: 'activity', label: 'Activity', href: hrefFor({ page: 'activity' }) },
  ] as const;

  const telemetry = !apiReachable
    ? { text: 'API unreachable', tone: 'unknown' }
    : pipeline
      ? {
          text: PIPELINE_TEXT[pipeline.status],
          tone: pipeline.status === 'CONNECTED' ? 'go' : pipeline.status === 'DEGRADED' ? 'caution' : 'unknown',
        }
      : { text: 'Connecting…', tone: 'unknown' };

  return (
    <header className="site-header">
      <a className="brand" href={hrefFor({ page: 'overview' })} aria-label="CloudPulse overview">
        <Activity aria-hidden size={22} strokeWidth={2.4} />
        <span>CloudPulse</span>
      </a>

      <nav aria-label="Main">
        <ul className="tabs">
          {tabs.map((tab) => (
            <li key={tab.page}>
              <a href={tab.href} aria-current={current === tab.page ? 'page' : undefined}>
                {tab.label}
                {tab.page === 'incidents' && activeIncidents ? (
                  <span className="tab-count" aria-label={`${activeIncidents} active`}>
                    {activeIncidents}
                  </span>
                ) : null}
              </a>
            </li>
          ))}
        </ul>
      </nav>

      <div className="header-end">
        <span className={`telemetry-chip tone-${telemetry.tone}`} role="status">
          <span className="dot" aria-hidden />
          {telemetry.text}
        </span>
        <a className="external" href={prometheusUrl ?? 'http://localhost:9090'} target="_blank" rel="noreferrer">
          Open Prometheus <ExternalLink aria-hidden size={14} />
        </a>
      </div>
    </header>
  );
}
