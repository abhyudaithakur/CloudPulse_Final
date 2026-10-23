import { describe, expect, it } from 'vitest';
import { niceTicks, scale, segments, yMax } from './chart';

describe('chart helpers', () => {
  it('NFR-04: missing samples split the line and become a gap, not a zero', () => {
    const { lines, gaps } = segments([
      { t: '2026-10-20T12:00:00Z', v: 0.04 },
      { t: '2026-10-20T12:00:05Z', v: null },
      { t: '2026-10-20T12:00:10Z', v: null },
      { t: '2026-10-20T12:00:15Z', v: 0.05 },
    ]);

    expect(lines).toHaveLength(2);
    expect(lines.flat().map((p) => p.v)).toEqual([0.04, 0.05]);
    expect(gaps).toEqual([[Date.parse('2026-10-20T12:00:05Z'), Date.parse('2026-10-20T12:00:15Z')]]);
  });

  it('FR-07: the y axis always includes the threshold with headroom', () => {
    expect(yMax([0.04, 0.05], 1)).toBe(2);
    expect(yMax([2.2], 1)).toBeCloseTo(2.53);
    expect(niceTicks(2)).toEqual([0, 0.5, 1, 1.5, 2]);
    expect(niceTicks(2.53)).toEqual([0, 1, 2, 3]);
    expect(niceTicks(0.1)).toEqual([0, 0.025, 0.05, 0.075, 0.1]);
  });

  it('FR-07: the threshold is drawn on the same scale as the data', () => {
    const top = niceTicks(yMax([0.2], 1)).at(-1)!;
    const y = scale([0, top], [200, 0]);
    // threshold 1.0 s on a 0–2 s axis sits exactly half way up: the midpoint chart drew it at 75%
    expect(y(1)).toBe(100);
  });
});
