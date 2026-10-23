import { api } from './api/client';
import { Header } from './components/Header';
import { useHashRoute } from './hooks/useHashRoute';
import { usePolling } from './hooks/usePolling';
import { ActivityPage } from './pages/ActivityPage';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { IncidentsPage } from './pages/IncidentsPage';
import { OverviewPage } from './pages/OverviewPage';

export default function App() {
  const route = useHashRoute();
  // The dashboard poll feeds the header (incident count, telemetry state) on every page.
  const dashboard = usePolling((signal) => api.dashboard(signal), 3000);

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <Header
        route={route}
        activeIncidents={dashboard.data?.counts.active}
        pipeline={dashboard.data?.pipeline}
        apiReachable={dashboard.error === undefined}
        prometheusUrl={dashboard.data?.prometheusUrl}
      />
      <main id="main" tabIndex={-1}>
        {route.page === 'overview' && <OverviewPage dashboard={dashboard} />}
        {route.page === 'incidents' && <IncidentsPage status={route.status} />}
        {route.page === 'incident' && <IncidentDetailPage id={route.id} />}
        {route.page === 'activity' && <ActivityPage />}
      </main>
      <footer className="site-footer">
        CloudPulse 2.0, a capstone project.{' '}
        {dashboard.data?.remediation.enabled
          ? `Running on Kubernetes: approved restarts may change namespace ${dashboard.data.remediation.namespace} only.`
          : 'Running in Docker Compose: CloudPulse detects and explains, and never changes a service.'}{' '}
        Prometheus, Spring Boot, PostgreSQL and React.
      </footer>
    </div>
  );
}
