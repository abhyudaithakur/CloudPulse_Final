import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RunbookOption } from '../api/types';
import { FaultControls } from './FaultControls';
import { ProbableSourcePanel } from './ProbableSourcePanel';
import { RemediationPanel } from './RemediationPanel';

const option = (overrides: Partial<RunbookOption> = {}): RunbookOption => ({
  id: 'RESTART_DEPLOYMENT',
  title: 'Restart payment-api',
  description: 'Rolling restart.',
  targetService: 'payment-api',
  namespace: 'cloudpulse-demo',
  deployment: 'payment-api',
  recommended: true,
  available: true,
  unavailableReason: null,
  ...overrides,
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ProbableSourcePanel', () => {
  it('FR-08 / UC-03: shows the verdict, the reasoning and links to the related incident', () => {
    render(
      <ProbableSourcePanel
        source={{
          verdict: 'DEPENDENCY',
          service: 'payment-api',
          statement: 'Probable source: payment-api. Inferred from the dependency map and timing.',
          evidence: ['checkout-api depends on payment-api (configured dependency map: checkout-api → payment-api).'],
          related: [
            {
              id: 3,
              key: 'CP-0003',
              serviceName: 'payment-api',
              ruleId: 'ERROR_RATE_SUSTAINED',
              title: 'Sustained HTTP 5xx error rate',
              relation: 'dependency',
              status: 'ACTIVE',
              severity: 'CRITICAL',
              breachStartedAt: '2026-10-20T12:00:00Z',
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('A dependency')).toBeInTheDocument();
    expect(screen.getByText(/Probable source: payment-api\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /CP-0003/ })).toHaveAttribute('href', '#/incidents/3');
  });

  it('UC-03 alt: an inconclusive result names no service', () => {
    render(
      <ProbableSourcePanel
        source={{ verdict: 'INCONCLUSIVE', service: null, statement: 'No probable source can be named yet.', evidence: [], related: [] }}
      />,
    );
    expect(screen.getByText('Inconclusive')).toBeInTheDocument();
  });
});

describe('RemediationPanel', () => {
  it('FR-11: approval needs a name and an explicit confirmation before it can be sent', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 1, status: 'APPROVED' }), { status: 202 }),
    );
    const onChanged = vi.fn();
    render(<RemediationPanel incidentId={7} runbooks={[option()]} remediations={[]} onChanged={onChanged} />);

    expect(screen.getByText('Recommended: probable source')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Review and approve' }));
    const approve = screen.getByRole('button', { name: 'Approve restart' });
    expect(approve).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/Your name/), { target: { value: 'Abhyudai' } });
    expect(approve).toBeDisabled();
    fireEvent.click(screen.getByRole('checkbox'));
    expect(approve).toBeEnabled();

    fireEvent.click(approve);
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/incidents/7/remediations/RESTART_DEPLOYMENT/approve');
    expect(JSON.parse(String(init?.body))).toEqual({
      targetService: 'payment-api',
      operator: 'Abhyudai',
      confirm: true,
    });
  });

  it('FR-10: in Docker Compose mode the runbook is shown but cannot be approved, with the reason', () => {
    render(
      <RemediationPanel
        incidentId={7}
        runbooks={[option({ available: false, unavailableReason: 'Remediation is off in Docker Compose mode.' })]}
        remediations={[]}
        onChanged={() => {}}
      />,
    );
    expect(screen.getByRole('button', { name: 'Not available' })).toBeDisabled();
    expect(screen.getByText(/off in Docker Compose mode/)).toBeInTheDocument();
  });

  it('FR-12: a finished remediation shows its outcome', () => {
    render(
      <RemediationPanel
        incidentId={7}
        runbooks={[]}
        remediations={[
          {
            id: 1,
            incidentId: 7,
            runbook: 'RESTART_DEPLOYMENT',
            targetService: 'payment-api',
            target: 'cloudpulse-demo/payment-api',
            approvedBy: 'Abhyudai',
            approvalNote: '',
            status: 'RECOVERED',
            inProgress: false,
            approvedAt: '2026-10-20T12:01:00Z',
            executedAt: '2026-10-20T12:01:02Z',
            rolledOutAt: '2026-10-20T12:01:30Z',
            verifyUntil: '2026-10-20T12:04:00Z',
            finishedAt: '2026-10-20T12:02:00Z',
            detail: 'Recovered: CP-0001 resolved 30 s after the rollout finished.',
          },
        ]}
        onChanged={() => {}}
      />,
    );
    expect(screen.getByText('Recovered')).toBeInTheDocument();
    expect(screen.getByText(/approved by Abhyudai/)).toBeInTheDocument();
  });
});

describe('FaultControls', () => {
  it('FR-09: a stuck service explains that only a restart clears it', () => {
    render(
      <FaultControls
        faults={[
          { service: 'checkout-api', mode: 'none', reachable: true, needsRestart: false },
          { service: 'payment-api', mode: 'stuck', reachable: true, needsRestart: true },
        ]}
        remediationEnabled={false}
        onChanged={() => {}}
      />,
    );

    fireEvent.click(screen.getByRole('radio', { name: 'payment-api' }));
    expect(screen.getByText('payment-api is stuck.')).toBeInTheDocument();
    expect(screen.getByText(/docker compose restart payment-api/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Stuck/ })).toHaveAttribute('aria-pressed', 'true');
  });
});
