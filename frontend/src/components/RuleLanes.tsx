import type { RuleSnapshot } from '../api/types';
import { formatCondition, formatValue } from '../lib/format';
import { laneName, windowProgress } from '../lib/rules';

/**
 * One lane per rule: live value, the violation condition, and how far the sustain or recovery
 * window has run. Missing telemetry is drawn hatched and labelled; it is never shown as healthy.
 */
export function RuleLanes({ rules }: { rules: RuleSnapshot[] }) {
  const services = [...new Set(rules.map((rule) => rule.serviceName))];
  return (
    <section className="lanes" aria-labelledby="lanes-title">
      <div className="section-head">
        <h2 id="lanes-title">Detection rules</h2>
        <p>Every rule runs on every service. An incident opens only after a rule stays violated for its whole window.</p>
      </div>
      <div className="lane-groups">
        {services.map((service) => (
          <div key={service} className="lane-group">
            <h3 className="lane-group-title">{service}</h3>
            <ol className="lane-list">
              {rules
                .filter((rule) => rule.serviceName === service)
                .map((rule) => (
                  <RuleLane key={rule.key ?? `${rule.serviceName}/${rule.ruleId}`} rule={rule} />
                ))}
            </ol>
          </div>
        ))}
      </div>
    </section>
  );
}

function RuleLane({ rule }: { rule: RuleSnapshot }) {
  const progress = windowProgress(rule);
  const percent = progress.fraction === null ? 0 : Math.round(progress.fraction * 100);
  const windowLabel =
    progress.fraction === null
      ? progress.text
      : `${progress.text} (${percent}% of the window)`;

  return (
    <li className={`lane tone-${progress.tone}`} data-phase={rule.phase}>
      <div className="lane-name">
        <h4>{laneName(rule.ruleId)}</h4>
        <p>{formatCondition(rule.threshold, rule.unit, rule.comparison, rule.sustainSeconds)}</p>
      </div>
      <p className="lane-value" aria-label={`Current value ${formatValue(rule.value, rule.unit)}`}>
        {formatValue(rule.value, rule.unit)}
      </p>
      <div className="lane-window">
        <div
          className="window-track"
          role="progressbar"
          aria-label={windowLabel}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress.fraction === null ? undefined : percent}
        >
          <div className="window-fill" style={{ width: `${percent}%` }} />
        </div>
        <p className="lane-phase">
          {progress.text}
          {(rule.phase === 'NO_DATA' || rule.phase === 'UNAVAILABLE') && rule.detail ? (
            <span className="lane-detail" title={rule.detail}>
              {rule.detail}
            </span>
          ) : null}
        </p>
      </div>
    </li>
  );
}
