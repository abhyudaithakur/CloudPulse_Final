import { useId } from 'react';
import { api } from '../api/client';
import type { RuleSnapshot } from '../api/types';
import { useElementWidth } from '../hooks/useElementWidth';
import { usePolling } from '../hooks/usePolling';
import { niceTicks, pathFor, scale, segments, yMax } from '../lib/chart';
import { formatValue } from '../lib/format';
import { laneName } from '../lib/rules';

const HEIGHT = 200;
const MARGIN = { top: 12, right: 12, bottom: 26, left: 52 };
const MINUTES = 5;

/**
 * Five minutes of a rule's metric straight from Prometheus (via the API). The threshold line is
 * drawn on the same scale as the data, and missing samples are hatched gaps, not zeros.
 */
export function MetricChart({ rule }: { rule: RuleSnapshot }) {
  const { ref, width } = useElementWidth<HTMLDivElement>();
  const hatchId = useId().replace(/:/g, '');
  const series = usePolling(
    (signal) => api.series(rule.serviceName, rule.ruleId, MINUTES, signal),
    5000,
    `${rule.serviceName}/${rule.ruleId}`,
  );
  const data = series.data;

  const { lines, gaps } = segments(data?.points ?? []);
  const values = lines.flat().map((p) => p.v);
  const ticks = niceTicks(yMax(values, rule.threshold));
  const top = ticks[ticks.length - 1];

  const start = data ? new Date(data.start).getTime() : Date.now() - MINUTES * 60_000;
  const end = data ? new Date(data.end).getTime() : Date.now();
  const innerWidth = Math.max(120, width - MARGIN.left - MARGIN.right);
  const innerHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const x = scale([start, end], [MARGIN.left, MARGIN.left + innerWidth]);
  const y = scale([0, top], [MARGIN.top + innerHeight, MARGIN.top]);
  const thresholdY = y(rule.threshold);
  const latest = lines.length ? lines[lines.length - 1][lines[lines.length - 1].length - 1] : undefined;
  const unavailableReason =
    data?.status === 'UNAVAILABLE'
      ? 'History unavailable: Prometheus is not answering'
      : series.error !== undefined
        ? 'History could not be loaded from the API'
        : null;
  const name = `${laneName(rule.ruleId)}, ${rule.serviceName}`;
  const summary = unavailableReason
    ? `${name}: ${unavailableReason}`
    : `${name} over the last ${MINUTES} minutes. Latest ${formatValue(latest?.v ?? null, rule.unit)}, threshold ${formatValue(rule.threshold, rule.unit)}.`;

  return (
    <figure className="chart">
      <figcaption>
        <span className="chart-title">{name}</span>
        <span className="chart-latest">{formatValue(latest?.v ?? null, rule.unit)}</span>
      </figcaption>
      <div ref={ref} className="chart-frame">
        <svg width={width} height={HEIGHT} role="img" aria-label={summary}>
          <defs>
            <pattern id={hatchId} width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <line x1="0" y1="0" x2="0" y2="8" className="hatch-line" />
            </pattern>
          </defs>

          {ticks.map((tick) => (
            <g key={tick} className="tick">
              <line x1={MARGIN.left} x2={MARGIN.left + innerWidth} y1={y(tick)} y2={y(tick)} />
              <text x={MARGIN.left - 8} y={y(tick)} dy="0.32em" textAnchor="end">
                {formatValue(tick, rule.unit)}
              </text>
            </g>
          ))}

          {gaps.map(([from, to]) => (
            <rect
              key={from}
              className="gap"
              x={x(from)}
              y={MARGIN.top}
              width={Math.max(2, x(to) - x(from))}
              height={innerHeight}
              fill={`url(#${hatchId})`}
            />
          ))}

          <line
            className="threshold"
            x1={MARGIN.left}
            x2={MARGIN.left + innerWidth}
            y1={thresholdY}
            y2={thresholdY}
          />
          <text className="threshold-label" x={MARGIN.left + innerWidth - 4} y={thresholdY - 6} textAnchor="end">
            threshold {formatValue(rule.threshold, rule.unit)}
          </text>

          {lines.map((line) => (
            <path key={line[0].t} className="series" d={pathFor(line, x, y)} />
          ))}
          {latest ? (
            <circle
              className={latest.v >= rule.threshold ? 'latest over' : 'latest'}
              cx={x(latest.t)}
              cy={y(latest.v)}
              r={4}
            />
          ) : null}

          <text className="axis" x={MARGIN.left} y={HEIGHT - 6}>
            {MINUTES} min ago
          </text>
          <text className="axis" x={MARGIN.left + innerWidth} y={HEIGHT - 6} textAnchor="end">
            now
          </text>

          {unavailableReason ? (
            <g className="unavailable">
              <rect
                x={MARGIN.left}
                y={MARGIN.top}
                width={innerWidth}
                height={innerHeight}
                fill={`url(#${hatchId})`}
              />
              <rect
                className="backdrop"
                x={MARGIN.left + innerWidth / 2 - Math.min(170, innerWidth / 2 - 4)}
                y={MARGIN.top + innerHeight / 2 - 16}
                width={Math.min(340, innerWidth - 8)}
                height={32}
                rx={3}
              />
              <text x={MARGIN.left + innerWidth / 2} y={MARGIN.top + innerHeight / 2} dy="0.35em" textAnchor="middle">
                {innerWidth < 340 ? 'History unavailable' : unavailableReason}
              </text>
            </g>
          ) : null}
        </svg>
      </div>
    </figure>
  );
}
