import type {
  ApprovalRequest,
  AuditEvent,
  Dashboard,
  FaultState,
  IncidentDetail,
  IncidentSummary,
  Remediation,
  Series,
} from './types';

/** An HTTP error from the API, carrying the RFC 9457 problem detail when there is one. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
  });
  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;
    try {
      const problem = (await response.json()) as { detail?: string };
      if (problem.detail) detail = problem.detail;
    } catch {
      // not JSON; keep the status line
    }
    throw new ApiError(response.status, detail);
  }
  return (await response.json()) as T;
}

export interface IncidentQuery {
  status?: 'ACTIVE' | 'RESOLVED';
  service?: string;
  rule?: string;
  limit?: number;
}

export const api = {
  dashboard: (signal?: AbortSignal) => request<Dashboard>('/dashboard', { signal }),

  incidents: (query: IncidentQuery = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (query.status) params.set('status', query.status);
    if (query.service) params.set('service', query.service);
    if (query.rule) params.set('rule', query.rule);
    params.set('limit', String(query.limit ?? 100));
    return request<IncidentSummary[]>(`/incidents?${params}`, { signal });
  },

  incident: (id: number, signal?: AbortSignal) =>
    request<IncidentDetail>(`/incidents/${id}`, { signal }),

  audit: (limit = 200, signal?: AbortSignal) =>
    request<AuditEvent[]>(`/audit?limit=${limit}`, { signal }),

  series: (service: string, ruleId: string, minutes = 5, signal?: AbortSignal) =>
    request<Series>(
      `/services/${encodeURIComponent(service)}/rules/${encodeURIComponent(ruleId)}/series?minutes=${minutes}`,
      { signal },
    ),

  setFault: (service: string, mode: string) =>
    request<FaultState>('/demo/fault', { method: 'POST', body: JSON.stringify({ service, mode }) }),

  /** FR-11: an explicit, attributed approval. Execution happens on the server afterwards. */
  approve: (incidentId: number, runbook: string, approval: ApprovalRequest) =>
    request<Remediation>(`/incidents/${incidentId}/remediations/${encodeURIComponent(runbook)}/approve`, {
      method: 'POST',
      body: JSON.stringify(approval),
    }),
};
