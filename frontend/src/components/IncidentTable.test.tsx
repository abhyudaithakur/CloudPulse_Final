import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { incident } from '../test/fixtures';
import { INCIDENT_COLUMNS, IncidentTable } from './IncidentTable';

describe('IncidentTable', () => {
  it('FR-07: every column header sits over the matching cell (regression: severity under "Detected")', () => {
    render(<IncidentTable incidents={[incident()]} now={Date.parse('2026-10-20T12:01:31Z')} />);

    const headers = screen.getAllByRole('columnheader').map((th) => th.textContent);
    expect(headers).toEqual([...INCIDENT_COLUMNS]);

    const row = screen.getAllByRole('row')[1];
    const cells = within(row).getAllByRole('cell');
    expect(cells).toHaveLength(headers.length);
    const cellUnder = (column: string) => cells[headers.indexOf(column)];

    expect(cellUnder('Incident')).toHaveTextContent('CP-0001');
    expect(cellUnder('Service')).toHaveTextContent('checkout-api');
    expect(cellUnder('Status')).toHaveTextContent('Active');
    expect(cellUnder('Severity')).toHaveTextContent('High');
    expect(cellUnder('Duration')).toHaveTextContent('1 min 0 s');
    expect(cellUnder('Peak')).toHaveTextContent('1.62 s');
  });

  it('FR-08: an incident caused by a dependency names its probable source', () => {
    render(<IncidentTable incidents={[incident({ correlation: 'DEPENDENCY', probableSource: 'payment-api' })]} />);

    expect(screen.getByText('probable source payment-api')).toBeInTheDocument();
  });

  it('FR-07: each incident links to its detail page', () => {
    render(<IncidentTable incidents={[incident({ id: 12, key: 'CP-0012' })]} />);

    expect(screen.getByRole('link', { name: /CP-0012/ })).toHaveAttribute('href', '#/incidents/12');
  });

  it('shows a call to action when there are no incidents', () => {
    render(<IncidentTable incidents={[]} />);

    expect(screen.getByText(/No incidents yet/)).toBeInTheDocument();
  });
});
