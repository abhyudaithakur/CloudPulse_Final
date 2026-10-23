import type { Comparison, MetricUnit } from '../api/types';

const EMPTY = '—';

/** A measurement in its unit. Null means "no usable measurement", shown as a dash, never 0. */
export function formatValue(value: number | null | undefined, unit: MetricUnit): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY;
  switch (unit) {
    case 'SECONDS':
      return value < 1 ? `${Math.round(value * 1000)} ms` : `${value.toFixed(2)} s`;
    case 'RATIO': {
      const percent = Math.round(value * 1000) / 10;
      return Number.isInteger(percent) ? `${percent}%` : `${percent.toFixed(1)}%`;
    }
    case 'BOOLEAN':
      return value >= 1 ? 'up' : 'down';
  }
}

/** The violation condition in words, e.g. "≥ 1.00 s for 30 s" or "down for 15 s". */
export function formatCondition(
  threshold: number,
  unit: MetricUnit,
  comparison: Comparison,
  sustainSeconds: number,
): string {
  if (unit === 'BOOLEAN') return `down for ${sustainSeconds} s`;
  const symbol = comparison === 'ABOVE' ? '≥' : '<';
  const limit = unit === 'SECONDS' ? `${threshold.toFixed(2)} s` : formatValue(threshold, unit);
  return `${symbol} ${limit} for ${sustainSeconds} s`;
}

export function formatClock(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return EMPTY;
  return new Date(iso).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/** "45 s", "3 min 12 s", "1 h 5 min". */
export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  if (s < 60) return `${s} s`;
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return `${minutes} min ${s % 60} s`;
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

export function secondsBetween(fromIso: string, toIso: string | null, now: number = Date.now()): number {
  const end = toIso ? new Date(toIso).getTime() : now;
  return (end - new Date(fromIso).getTime()) / 1000;
}

/** "LATENCY_P95_SUSTAINED" → "Latency p95 sustained" for places that show raw rule ids. */
export function humanize(id: string): string {
  const words = id.toLowerCase().split('_');
  return words.map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w)).join(' ');
}
