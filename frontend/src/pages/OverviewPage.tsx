import { useState } from 'react';
import type { Dashboard } from '../api/types';
import { FaultControls } from '../components/FaultControls';
import { IncidentTable } from '../components/IncidentTable';
import { MetricChart } from '../components/MetricChart';
import { RuleLanes } from '../components/RuleLanes';
import { ServiceMap } from '../components/ServiceMap';
import { StatusBand } from '../components/StatusBand';
import { hrefFor } from '../hooks/useHashRoute';
import type { Polled } from '../hooks/usePolling';

const RECENT = 6;

export function OverviewPage({ dashboard }: { dashboard: Polled<Dashboard> }) {
  const data = dashboard.data;
  const [chartService, setChartService] = useState<string | null>(null);
  const services = data?.services.map((s) => s.name) ?? [];
  const selected = chartService && services.includes(chartService) ? chartService : services[0];
  const charted =
    data?.rules.filter((rule) => rule.unit !== 'BOOLEAN' && rule.serviceName === selected) ?? [];

  return (
    <>
      <StatusBand dashboard={data} apiError={dashboard.error} updatedAt={dashboard.updatedAt} />

      {data ? (
        <>
          <ServiceMap dashboard={data} />

          <RuleLanes rules={data.rules} />

          <div className="workbench">
            <section className="charts" aria-labelledby="charts-title">
              <div className="section-head">
                <h2 id="charts-title">Last five minutes</h2>
                <div className="segmented" role="radiogroup" aria-label="Service to chart">
                  {services.map((name) => (
                    <button
                      key={name}
                      type="button"
                      role="radio"
                      aria-checked={name === selected}
                      onClick={() => setChartService(name)}
                    >
                      {name}
                    </button>
                  ))}
                </div>
              </div>
              <p className="charts-note">From Prometheus. Hatched areas had no usable samples.</p>
              {charted.map((rule) => (
                <MetricChart key={rule.key ?? `${rule.serviceName}/${rule.ruleId}`} rule={rule} />
              ))}
            </section>
            <FaultControls
              faults={data.faults}
              remediationEnabled={data.remediation.enabled}
              onChanged={dashboard.refresh}
            />
          </div>

          <section className="recent" aria-labelledby="recent-title">
            <div className="section-head">
              <h2 id="recent-title">Recent incidents</h2>
              <p>
                {data.counts.total} recorded.{' '}
                <a href={hrefFor({ page: 'incidents' })}>See all incidents</a>
              </p>
            </div>
            <IncidentTable incidents={data.incidents.slice(0, RECENT)} compact />
          </section>
        </>
      ) : null}
    </>
  );
}
