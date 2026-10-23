import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { dashboard, incident, service } from '../test/fixtures';
import { flowOrder, ServiceMap } from './ServiceMap';

describe('ServiceMap', () => {
  it('FR-08: services read in the direction requests flow, caller first', () => {
    const ordered = flowOrder([
      service({ name: 'payment-api', dependsOn: [] }),
      service({ name: 'checkout-api', dependsOn: ['payment-api'] }),
    ]);
    expect(ordered.map((s) => s.name)).toEqual(['checkout-api', 'payment-api']);
  });

  it('FR-08 / FR-09: marks the probable source and a stuck service', () => {
    const data = dashboard({
      services: [
        service({ health: 'DEGRADED', activeIncidents: 1 }),
        service({ name: 'payment-api', health: 'DEGRADED', activeIncidents: 1, dependsOn: [] }),
      ],
      faults: [
        { service: 'checkout-api', mode: 'none', reachable: true, needsRestart: false },
        { service: 'payment-api', mode: 'stuck', reachable: true, needsRestart: true },
      ],
      incidents: [incident({ correlation: 'DEPENDENCY', probableSource: 'payment-api' })],
    });
    render(<ServiceMap dashboard={data} />);

    const payment = screen.getByRole('article', { name: 'payment-api: Degraded' });
    expect(within(payment).getByText('needs restart')).toBeInTheDocument();
    expect(within(payment).getByText(/Probable source of an active incident/)).toBeInTheDocument();
    const checkout = screen.getByRole('article', { name: 'checkout-api: Degraded' });
    expect(within(checkout).queryByText(/Probable source/)).not.toBeInTheDocument();
    expect(within(checkout).getByText('payment-api')).toBeInTheDocument();
  });
});
