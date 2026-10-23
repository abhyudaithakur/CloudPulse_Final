import { useState } from 'react';
import { api } from '../api/client';
import type { FaultState } from '../api/types';

const MODES = [
  { mode: 'none', label: 'Normal', hint: '40 ms, no errors' },
  { mode: 'high-latency', label: 'High latency', hint: '1.4 s per request' },
  { mode: 'extreme-latency', label: 'Extreme latency', hint: '2.2 s per request' },
  { mode: 'error-storm', label: 'Error storm', hint: '3 in 10 requests fail' },
  { mode: 'stuck', label: 'Stuck', hint: '3 s per request; only a restart clears it' },
] as const;

const LABEL: Record<string, string> = Object.fromEntries(MODES.map((m) => [m.mode, m.label.toLowerCase()]));

interface FaultControlsProps {
  faults: FaultState[];
  /** True when CloudPulse runs on Kubernetes and can restart a deployment after approval. */
  remediationEnabled: boolean;
  onChanged: () => void;
}

/** Demo-only controls (UC-06, FR-09): inject and clear repeatable faults on either service. */
export function FaultControls({ faults, remediationEnabled, onChanged }: FaultControlsProps) {
  const [service, setService] = useState<string>(faults[0]?.service ?? 'checkout-api');
  const [pending, setPending] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const fault = faults.find((f) => f.service === service);

  async function apply(mode: string) {
    setPending(mode);
    setMessage(null);
    try {
      const state = await api.setFault(service, mode);
      setMessage({
        text:
          state.mode === 'none'
            ? `Fault cleared on ${service}. The incident resolves after 10 s back under threshold, once old samples leave the 1-minute window.`
            : state.needsRestart
              ? `${service} is now stuck. Clearing it here will be refused, as with a real wedged process.`
              : `${service} set to ${LABEL[state.mode] ?? state.mode}. Detection waits for the full sustain window before opening an incident.`,
        error: false,
      });
      onChanged();
    } catch (e) {
      setMessage({
        text: `Fault not changed: ${e instanceof Error ? e.message : 'the demo service did not answer'}.`,
        error: true,
      });
    } finally {
      setPending(null);
    }
  }

  return (
    <section className="faults" aria-labelledby="faults-title">
      <div className="section-head">
        <h2 id="faults-title">Inject a fault</h2>
        <p>Demo controls. Every change is recorded in Activity.</p>
      </div>

      <div className="segmented" role="radiogroup" aria-label="Service">
        {faults.map((f) => (
          <button
            key={f.service}
            type="button"
            role="radio"
            aria-checked={f.service === service}
            onClick={() => {
              setService(f.service);
              setMessage(null);
            }}
          >
            {f.service}
          </button>
        ))}
      </div>

      <div className="fault-grid" role="group" aria-label={`Fault mode for ${service}`}>
        {MODES.map(({ mode, label, hint }) => {
          const active = fault?.mode === mode;
          return (
            <button
              key={mode}
              type="button"
              className={`fault fault-${mode}`}
              aria-pressed={active}
              disabled={pending !== null || active}
              onClick={() => void apply(mode)}
            >
              <span className="fault-label">{pending === mode ? 'Applying…' : label}</span>
              <span className="fault-hint">{hint}</span>
            </button>
          );
        })}
      </div>

      {fault && !fault.reachable ? (
        <p className="fault-message error">{service} is not answering, so its fault mode is unknown.</p>
      ) : null}
      {fault?.needsRestart ? (
        <p className="fault-message warn" role="note">
          <strong>{service} is stuck.</strong>{' '}
          {remediationEnabled
            ? 'Open its incident and approve the restart runbook; CloudPulse restarts only that deployment and checks it recovered.'
            : `Restart it by hand (docker compose restart ${service}), or run CloudPulse on Kubernetes to approve a guarded restart.`}
        </p>
      ) : null}
      {message ? (
        <p className={message.error ? 'fault-message error' : 'fault-message'} role="status">
          {message.text}
        </p>
      ) : null}
    </section>
  );
}
