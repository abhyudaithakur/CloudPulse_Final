import type { RulePhase, RuleSnapshot } from '../api/types';
import { humanize } from './format';

/** Operator-facing names for rule ids. Unknown rules fall back to a readable form of the id. */
const LANE_NAMES: Record<string, string> = {
  LATENCY_P95_SUSTAINED: 'Request latency, p95',
  ERROR_RATE_SUSTAINED: 'Server errors (5xx)',
  TARGET_AVAILABILITY: 'Reachable by Prometheus',
};

export function laneName(ruleId: string): string {
  return LANE_NAMES[ruleId] ?? humanize(ruleId);
}

export interface WindowProgress {
  /** 0..1 share of the window that has elapsed; null when no window is running. */
  fraction: number | null;
  /** What the lane says about the window. */
  text: string;
  tone: 'quiet' | 'caution' | 'stop' | 'go' | 'unknown';
}

function elapsed(fromIso: string | null, toIso: string | null): number {
  if (!fromIso || !toIso) return 0;
  return Math.max(0, (new Date(toIso).getTime() - new Date(fromIso).getTime()) / 1000);
}

/**
 * Describes where a rule is inside its sustain or recovery window, from server timestamps only
 * (evaluatedAt), so a skewed browser clock cannot misreport progress.
 */
export function windowProgress(rule: RuleSnapshot): WindowProgress {
  const phase: RulePhase = rule.phase;
  switch (phase) {
    case 'PENDING': {
      const s = Math.min(rule.sustainSeconds, Math.floor(elapsed(rule.breachStartedAt, rule.evaluatedAt)));
      return {
        fraction: rule.sustainSeconds > 0 ? s / rule.sustainSeconds : 1,
        text: `Over threshold for ${s} of ${rule.sustainSeconds} s`,
        tone: 'caution',
      };
    }
    case 'BREACHING':
      return { fraction: 1, text: 'Incident open', tone: 'stop' };
    case 'RECOVERING': {
      const s = Math.min(rule.recoverySeconds, Math.floor(elapsed(rule.recoveryStartedAt, rule.evaluatedAt)));
      return {
        fraction: rule.recoverySeconds > 0 ? s / rule.recoverySeconds : 1,
        text: `Back within threshold for ${s} of ${rule.recoverySeconds} s`,
        tone: 'go',
      };
    }
    case 'HEALTHY':
      return { fraction: null, text: 'Within threshold', tone: 'quiet' };
    case 'NO_DATA':
      return { fraction: null, text: 'No data to evaluate', tone: 'unknown' };
    case 'UNAVAILABLE':
      return { fraction: null, text: 'Telemetry unavailable', tone: 'unknown' };
    case 'STARTING':
      return { fraction: null, text: 'Waiting for the first evaluation', tone: 'unknown' };
  }
}
