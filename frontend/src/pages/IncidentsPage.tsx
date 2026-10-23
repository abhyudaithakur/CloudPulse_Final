import { api } from '../api/client';
import { IncidentTable } from '../components/IncidentTable';
import { hrefFor } from '../hooks/useHashRoute';
import { usePolling } from '../hooks/usePolling';

const FILTERS = [
  { label: 'All', status: undefined },
  { label: 'Active', status: 'ACTIVE' },
  { label: 'Resolved', status: 'RESOLVED' },
] as const;

export function IncidentsPage({ status }: { status?: 'ACTIVE' | 'RESOLVED' }) {
  const incidents = usePolling(
    (signal) => api.incidents({ status, limit: 200 }, signal),
    4000,
    status ?? 'all',
  );

  return (
    <section className="page" aria-labelledby="incidents-title">
      <div className="page-head">
        <h1 id="incidents-title">Incidents</h1>
        <nav aria-label="Filter incidents" className="filters">
          {FILTERS.map((filter) => (
            <a
              key={filter.label}
              href={hrefFor({ page: 'incidents', status: filter.status })}
              aria-current={filter.status === status ? 'true' : undefined}
            >
              {filter.label}
            </a>
          ))}
        </nav>
      </div>
      {incidents.error && !incidents.data ? (
        <p className="notice error">Incidents could not be loaded: {incidents.error}</p>
      ) : null}
      {incidents.stale ? (
        <p className="notice">API unreachable. Showing the list from the last successful refresh.</p>
      ) : null}
      {incidents.data ? <IncidentTable incidents={incidents.data} /> : null}
    </section>
  );
}
