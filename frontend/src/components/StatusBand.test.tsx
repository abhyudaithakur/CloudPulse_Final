import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { dashboard, incident, rule, service } from '../test/fixtures';
import { RuleLanes } from './RuleLanes';
import { StatusBand } from './StatusBand';

describe('StatusBand', () => {
  it('UC-01: healthy service with every rule in threshold', () => {
    const data = dashboard({
      rules: [rule(), rule({ ruleId: 'ERROR_RATE_SUSTAINED', unit: 'RATIO', threshold: 0.05, value: 0 })],
    });
    render(<StatusBand dashboard={data} apiError={undefined} updatedAt={Date.now()} />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Healthy');
    expect(screen.getByText('All 2 rules on 2 services are within threshold.')).toBeInTheDocument();
  });

  it('NFR-04 / UC-01 alt: telemetry down says "Health unknown", never "Healthy"', () => {
    const data = dashboard({
      health: 'UNKNOWN',
      pipeline: { ...dashboard().pipeline, status: 'UNAVAILABLE', lastError: 'Connection refused' },
      services: [service({ health: 'UNKNOWN' }), service({ name: 'payment-api', health: 'UNKNOWN', dependsOn: [] })],
      rules: [rule({ phase: 'UNAVAILABLE', value: null })],
    });
    render(<StatusBand dashboard={data} apiError={undefined} updatedAt={Date.now()} />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Health unknown');
    expect(screen.getByText(/Prometheus is not answering/)).toBeInTheDocument();
  });

  it('FR-06: degraded service names the active incident and its severity', () => {
    const data = dashboard({
      health: 'DEGRADED',
      services: [service({ health: 'DEGRADED', activeIncidents: 1 }), service({ name: 'payment-api', dependsOn: [] })],
      incidents: [incident()],
      counts: { active: 1, total: 1 },
    });
    render(<StatusBand dashboard={data} apiError={undefined} updatedAt={Date.now()} />);

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Degraded');
    expect(
      screen.getByText(/1 active incident: sustained high request latency on checkout-api \(high\)\.$/),
    ).toBeInTheDocument();
  });

  it('FR-08: the headline names the probable source when a dependency caused the incident', () => {
    const data = dashboard({
      health: 'DEGRADED',
      incidents: [
        incident({ correlation: 'DEPENDENCY', probableSource: 'payment-api' }),
        incident({ id: 2, key: 'CP-0002', serviceName: 'payment-api', correlation: 'SELF', probableSource: 'payment-api' }),
      ],
    });
    render(<StatusBand dashboard={data} apiError={undefined} updatedAt={Date.now()} />);

    expect(screen.getByText(/2 active incidents: .* Probable source: payment-api\./)).toBeInTheDocument();
  });

  it('NFR-04: one service without data makes the whole system "Health unknown", naming it', () => {
    const data = dashboard({
      health: 'UNKNOWN',
      rules: [rule(), rule({ serviceName: 'payment-api', phase: 'NO_DATA', value: null })],
    });
    render(<StatusBand dashboard={data} apiError={undefined} updatedAt={Date.now()} />);

    expect(screen.getByText(/No usable data for payment-api \(request latency, p95\)/)).toBeInTheDocument();
  });

  it('NFR-04: when the API stops answering, stale data is labelled as stale', () => {
    render(<StatusBand dashboard={dashboard()} apiError="Failed to fetch" updatedAt={Date.parse('2026-10-20T12:00:31Z')} />);

    expect(screen.getByText(/API unreachable. Showing data received at/)).toBeInTheDocument();
  });
});

describe('RuleLanes', () => {
  it('NFR-04: a rule with no data shows a dash and says why, instead of a stale value', () => {
    render(<RuleLanes rules={[rule({ phase: 'NO_DATA', value: null, detail: 'query returned NaN' })]} />);

    expect(screen.getByLabelText('Current value —')).toBeInTheDocument();
    expect(screen.getByText(/No data to evaluate/)).toBeInTheDocument();
  });

  it('FR-03: a pending breach shows sustain-window progress', () => {
    render(
      <RuleLanes
        rules={[rule({ phase: 'PENDING', value: 1.42, breachStartedAt: '2026-10-20T12:00:12Z' })]}
      />,
    );

    expect(screen.getByText('Over threshold for 18 of 30 s')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '60');
  });
});
