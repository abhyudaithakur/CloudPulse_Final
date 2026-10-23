import type { Dashboard, IncidentSummary, RuleSnapshot, ServiceView } from '../api/types';

export function rule(overrides: Partial<RuleSnapshot> = {}): RuleSnapshot {
  const ruleId = overrides.ruleId ?? 'LATENCY_P95_SUSTAINED';
  const serviceName = overrides.serviceName ?? 'checkout-api';
  return {
    key: `${serviceName}/${ruleId}`,
    ruleId: 'LATENCY_P95_SUSTAINED',
    serviceName: 'checkout-api',
    title: 'Sustained high request latency',
    unit: 'SECONDS',
    comparison: 'ABOVE',
    threshold: 1,
    sustainSeconds: 30,
    recoverySeconds: 10,
    phase: 'HEALTHY',
    value: 0.042,
    telemetryStatus: 'OK',
    detail: '',
    evaluatedAt: '2026-10-20T12:00:30Z',
    breachStartedAt: null,
    recoveryStartedAt: null,
    ...overrides,
  };
}

export function incident(overrides: Partial<IncidentSummary> = {}): IncidentSummary {
  return {
    id: 1,
    key: 'CP-0001',
    ruleId: 'LATENCY_P95_SUSTAINED',
    serviceName: 'checkout-api',
    title: 'Sustained high request latency',
    status: 'ACTIVE',
    severity: 'HIGH',
    unit: 'SECONDS',
    comparison: 'ABOVE',
    threshold: 1,
    lastValue: 1.6,
    peakValue: 1.62,
    sustainSeconds: 30,
    recoverySeconds: 10,
    breachStartedAt: '2026-10-20T12:00:00Z',
    openedAt: '2026-10-20T12:00:31Z',
    resolvedAt: null,
    lastObservedAt: '2026-10-20T12:01:00Z',
    detectionDelaySeconds: 1,
    summary: 'Sustained high request latency: 1.600 s ≥ 1.000 s held for 31 s.',
    probableSource: 'checkout-api',
    correlation: 'SELF',
    ...overrides,
  };
}

export function service(overrides: Partial<ServiceView> = {}): ServiceView {
  return {
    name: 'checkout-api',
    health: 'HEALTHY',
    activeIncidents: 0,
    ruleIds: [],
    dependsOn: ['payment-api'],
    deployment: 'checkout-api',
    ...overrides,
  };
}

export function dashboard(overrides: Partial<Dashboard> = {}): Dashboard {
  return {
    generatedAt: '2026-10-20T12:00:31Z',
    health: 'HEALTHY',
    pipeline: {
      status: 'CONNECTED',
      source: 'prometheus',
      lastAttemptAt: '2026-10-20T12:00:30Z',
      lastSuccessAt: '2026-10-20T12:00:30Z',
      lastError: '',
      failingQueries: 0,
      totalQueries: 3,
      evaluationIntervalMs: 5000,
    },
    services: [
      service(),
      service({ name: 'payment-api', dependsOn: [], deployment: 'payment-api' }),
    ],
    rules: [rule()],
    counts: { active: 0, total: 0 },
    faults: [
      { service: 'checkout-api', mode: 'none', reachable: true, needsRestart: false },
      { service: 'payment-api', mode: 'none', reachable: true, needsRestart: false },
    ],
    incidents: [],
    remediation: {
      enabled: false,
      mode: 'disabled',
      namespace: 'cloudpulse-demo',
      verificationWindowSeconds: 150,
      note: 'Remediation needs Kubernetes.',
    },
    prometheusUrl: 'http://localhost:9090',
    ...overrides,
  };
}
