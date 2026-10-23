import type { SeriesPoint } from '../api/types';

export interface Point {
  t: number;
  v: number;
}

/** Splits a series at missing samples so gaps are drawn as gaps, never bridged or zeroed. */
export function segments(points: SeriesPoint[]): { lines: Point[][]; gaps: [number, number][] } {
  const lines: Point[][] = [];
  const gaps: [number, number][] = [];
  let current: Point[] = [];
  let gapStart: number | null = null;

  for (const p of points) {
    const t = new Date(p.t).getTime();
    if (p.v === null || !Number.isFinite(p.v)) {
      if (current.length) lines.push(current);
      current = [];
      if (gapStart === null) gapStart = t;
    } else {
      if (gapStart !== null) {
        gaps.push([gapStart, t]);
        gapStart = null;
      }
      current.push({ t, v: p.v });
    }
  }
  if (current.length) lines.push(current);
  if (gapStart !== null && points.length) {
    gaps.push([gapStart, new Date(points[points.length - 1].t).getTime()]);
  }
  return { lines, gaps };
}

/** Upper bound of the y axis: always shows the threshold with headroom above it. */
export function yMax(values: number[], threshold: number): number {
  const top = Math.max(threshold * 2, ...values.map((v) => v * 1.15));
  return top > 0 ? top : 1;
}

/** Round tick values (steps of 1, 2, 2.5 or 5 × 10ⁿ) from 0 up to at least `max`. */
export function niceTicks(max: number, target = 4): number[] {
  if (!(max > 0)) return [0, 1];
  const rough = max / target;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rough) ?? rough;
  const ticks: number[] = [];
  for (let v = 0; v <= max + step * 1e-9; v += step) ticks.push(Number(v.toPrecision(12)));
  if (ticks[ticks.length - 1] < max) ticks.push(Number((ticks[ticks.length - 1] + step).toPrecision(12)));
  return ticks;
}

export function scale(domain: [number, number], range: [number, number]): (x: number) => number {
  const [d0, d1] = domain;
  const [r0, r1] = range;
  const span = d1 - d0 || 1;
  return (x) => r0 + ((x - d0) / span) * (r1 - r0);
}

export function pathFor(line: Point[], x: (t: number) => number, y: (v: number) => number): string {
  return line.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join(' ');
}
