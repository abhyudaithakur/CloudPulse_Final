import { describe, expect, it } from 'vitest';
import { formatCondition, formatDuration, formatValue, humanize } from './format';

describe('formatValue', () => {
  it('NFR-04: a missing measurement renders as a dash, never as 0', () => {
    expect(formatValue(null, 'SECONDS')).toBe('—');
    expect(formatValue(undefined, 'RATIO')).toBe('—');
    expect(formatValue(Number.NaN, 'SECONDS')).toBe('—');
  });

  it('FR-07: values are shown in their unit', () => {
    expect(formatValue(0.042, 'SECONDS')).toBe('42 ms');
    expect(formatValue(1.4321, 'SECONDS')).toBe('1.43 s');
    expect(formatValue(0.3, 'RATIO')).toBe('30%');
    expect(formatValue(0.05, 'RATIO')).toBe('5%');
    expect(formatValue(0.075, 'RATIO')).toBe('7.5%');
    expect(formatValue(0.0333, 'RATIO')).toBe('3.3%');
    expect(formatValue(0, 'BOOLEAN')).toBe('down');
    expect(formatValue(1, 'BOOLEAN')).toBe('up');
  });
});

describe('formatCondition', () => {
  it('FR-07: describes the violation condition in words', () => {
    expect(formatCondition(1, 'SECONDS', 'ABOVE', 30)).toBe('≥ 1.00 s for 30 s');
    expect(formatCondition(0.05, 'RATIO', 'ABOVE', 30)).toBe('≥ 5% for 30 s');
    expect(formatCondition(1, 'BOOLEAN', 'BELOW', 15)).toBe('down for 15 s');
  });
});

describe('formatDuration and humanize', () => {
  it('formats durations and rule ids for people', () => {
    expect(formatDuration(45)).toBe('45 s');
    expect(formatDuration(192)).toBe('3 min 12 s');
    expect(formatDuration(3900)).toBe('1 h 5 min');
    expect(humanize('SEVERITY_ESCALATED')).toBe('Severity escalated');
  });
});
