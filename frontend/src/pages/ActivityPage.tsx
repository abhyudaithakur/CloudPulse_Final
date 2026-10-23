import { api } from '../api/client';
import { hrefFor } from '../hooks/useHashRoute';
import { usePolling } from '../hooks/usePolling';
import { formatDateTime, humanize } from '../lib/format';

/** The append-only audit trail (FR-13): incidents, fault changes and telemetry outages. */
export function ActivityPage() {
  const audit = usePolling((signal) => api.audit(200, signal), 5000);

  return (
    <section className="page" aria-labelledby="activity-title">
      <div className="page-head">
        <h1 id="activity-title">Activity</h1>
        <p>Every state change, newest first. The audit table rejects updates and deletes.</p>
      </div>
      {audit.error && !audit.data ? (
        <p className="notice error">Activity could not be loaded: {audit.error}</p>
      ) : null}
      {audit.data && audit.data.length === 0 ? (
        <div className="empty">
          <p>Nothing recorded yet. Fault changes, incidents and telemetry outages appear here.</p>
        </div>
      ) : null}
      {audit.data && audit.data.length > 0 ? (
        <div className="table-scroll">
          <table className="activity-table">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Event</th>
                <th scope="col">By</th>
                <th scope="col">Target</th>
                <th scope="col">Result</th>
                <th scope="col">Details</th>
              </tr>
            </thead>
            <tbody>
              {audit.data.map((event) => (
                <tr key={event.id} className={event.result === 'FAILURE' ? 'row-failed' : undefined}>
                  <td className="num">{formatDateTime(event.occurredAt)}</td>
                  <td>{humanize(event.action)}</td>
                  <td>{event.actor}</td>
                  <td>
                    {event.incidentId ? (
                      <a href={hrefFor({ page: 'incident', id: event.incidentId })}>{event.target}</a>
                    ) : (
                      event.target
                    )}
                  </td>
                  <td>{event.result === 'SUCCESS' ? 'Success' : 'Failed'}</td>
                  <td className="details">{event.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  );
}
