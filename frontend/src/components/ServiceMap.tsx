import { ArrowRight } from 'lucide-react';
import type { Dashboard, FaultState, ServiceHealth, ServiceView } from '../api/types';
import { hrefFor } from '../hooks/useHashRoute';

const HEALTH_TEXT: Record<ServiceHealth, string> = {
  STARTING: 'Starting',
  HEALTHY: 'Healthy',
  DEGRADED: 'Degraded',
  UNAVAILABLE: 'Unavailable',
  UNKNOWN: 'Unknown',
};

/** Callers before callees, so the map reads in the direction requests flow. */
export function flowOrder(services: ServiceView[]): ServiceView[] {
  const byName = new Map(services.map((s) => [s.name, s]));
  const calledBy = new Set(services.flatMap((s) => s.dependsOn));
  const ordered: ServiceView[] = [];
  const visit = (service: ServiceView) => {
    if (ordered.includes(service)) return;
    ordered.push(service);
    service.dependsOn.forEach((name) => {
      const next = byName.get(name);
      if (next) visit(next);
    });
  };
  services.filter((s) => !calledBy.has(s.name)).forEach(visit);
  services.forEach(visit); // anything left (should not happen without cycles)
  return ordered;
}

/**
 * The monitored services and who calls whom (FR-08's dependency map), with each service's health,
 * active incidents and demo fault. A service named as the probable source of an active incident
 * elsewhere is marked, so the operator sees where to look first.
 */
export function ServiceMap({ dashboard }: { dashboard: Dashboard }) {
  const faults = new Map<string, FaultState>(dashboard.faults.map((f) => [f.service, f]));
  const sources = new Set(
    dashboard.incidents
      .filter((i) => i.status === 'ACTIVE' && i.correlation === 'DEPENDENCY' && i.probableSource)
      .map((i) => i.probableSource as string),
  );
  const ordered = flowOrder(dashboard.services);

  return (
    <section className="service-map" aria-labelledby="map-title">
      <div className="section-head">
        <h2 id="map-title">Services</h2>
        <p>Traffic enters at the left. A problem in a service it calls shows up in the caller too.</p>
      </div>
      <ol className="service-flow">
        {ordered.map((service, index) => {
          const fault = faults.get(service.name);
          return (
            <li key={service.name} className="service-flow-item">
              {index > 0 ? (
                <span className="calls" aria-hidden>
                  <ArrowRight size={18} />
                  <span>calls</span>
                </span>
              ) : null}
              <article
                className={`service-card health-${service.health.toLowerCase()}`}
                aria-label={`${service.name}: ${HEALTH_TEXT[service.health]}`}
              >
                <header>
                  <h3>{service.name}</h3>
                  <span className="service-health">{HEALTH_TEXT[service.health]}</span>
                </header>
                <dl>
                  <div>
                    <dt>Active incidents</dt>
                    <dd>
                      {service.activeIncidents > 0 ? (
                        <a href={hrefFor({ page: 'incidents', status: 'ACTIVE' })}>{service.activeIncidents}</a>
                      ) : (
                        0
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt>Demo fault</dt>
                    <dd>
                      {!fault || !fault.reachable ? 'not answering' : fault.mode}
                      {fault?.needsRestart ? <strong className="stuck-tag">needs restart</strong> : null}
                    </dd>
                  </div>
                  <div>
                    <dt>Calls</dt>
                    <dd>{service.dependsOn.length ? service.dependsOn.join(', ') : 'nothing (leaf)'}</dd>
                  </div>
                </dl>
                {sources.has(service.name) ? (
                  <p className="source-flag">Probable source of an active incident upstream</p>
                ) : null}
              </article>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
