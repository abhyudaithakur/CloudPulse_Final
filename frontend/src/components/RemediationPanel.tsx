import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import type { Remediation, RemediationStatus, RunbookOption } from '../api/types';
import { formatClock, formatDateTime } from '../lib/format';

const OPERATOR_KEY = 'cloudpulse.operator';

function rememberedOperator(): string {
  try {
    return window.localStorage.getItem(OPERATOR_KEY) ?? '';
  } catch {
    return '';
  }
}

function rememberOperator(name: string) {
  try {
    window.localStorage.setItem(OPERATOR_KEY, name);
  } catch {
    // storage unavailable: the name is simply not remembered
  }
}

const STEPS: { status: RemediationStatus; label: string }[] = [
  { status: 'APPROVED', label: 'Approved' },
  { status: 'EXECUTING', label: 'Restart sent' },
  { status: 'ROLLING_OUT', label: 'Rolling out' },
  { status: 'VERIFYING', label: 'Verifying' },
];

const OUTCOME: Partial<Record<RemediationStatus, { label: string; tone: string }>> = {
  RECOVERED: { label: 'Recovered', tone: 'go' },
  NOT_RECOVERED: { label: 'Not recovered', tone: 'stop' },
  FAILED: { label: 'Failed', tone: 'stop' },
};

interface RemediationPanelProps {
  incidentId: number;
  runbooks: RunbookOption[];
  remediations: Remediation[];
  onChanged: () => void;
}

/**
 * Guarded remediation (FR-10 to FR-12, UC-04). A restart runs only after an operator names
 * themselves and confirms the exact deployment; progress and outcome come from the server.
 */
export function RemediationPanel({ incidentId, runbooks, remediations, onChanged }: RemediationPanelProps) {
  const [open, setOpen] = useState<string | null>(null);

  return (
    <section className="runbooks" aria-labelledby="runbooks-title">
      <h2 id="runbooks-title">Remediation</h2>
      {runbooks.length === 0 ? (
        <p>No predefined runbook applies to this incident.</p>
      ) : (
        <ul>
          {runbooks.map((runbook) => {
            const key = `${runbook.id}:${runbook.targetService}`;
            return (
              <li key={key} className={runbook.recommended ? 'recommended' : undefined}>
                <div className="runbook-row">
                  <div>
                    <h3>
                      {runbook.title}
                      {runbook.recommended ? <span className="tag">Recommended: probable source</span> : null}
                    </h3>
                    <p>{runbook.description}</p>
                    <p className="runbook-target">
                      Changes deployment <code>{runbook.deployment}</code> in namespace{' '}
                      <code>{runbook.namespace}</code>, nothing else.
                    </p>
                    {!runbook.available && runbook.unavailableReason ? (
                      <p className="runbook-unavailable">{runbook.unavailableReason}</p>
                    ) : null}
                  </div>
                  {open !== key ? (
                    <button type="button" disabled={!runbook.available} onClick={() => setOpen(key)}>
                      {runbook.available ? 'Review and approve' : 'Not available'}
                    </button>
                  ) : null}
                </div>
                {open === key ? (
                  <ApprovalForm
                    incidentId={incidentId}
                    runbook={runbook}
                    onCancel={() => setOpen(null)}
                    onApproved={() => {
                      setOpen(null);
                      onChanged();
                    }}
                  />
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {remediations.length ? <History remediations={remediations} /> : null}
    </section>
  );
}

interface ApprovalFormProps {
  incidentId: number;
  runbook: RunbookOption;
  onCancel: () => void;
  onApproved: () => void;
}

export function ApprovalForm({ incidentId, runbook, onCancel, onApproved }: ApprovalFormProps) {
  const [operator, setOperator] = useState(rememberedOperator);
  const [confirmed, setConfirmed] = useState(false);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = operator.trim().length > 0 && confirmed && !submitting;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!ready) return;
    setSubmitting(true);
    setError(null);
    try {
      await api.approve(incidentId, runbook.id, {
        targetService: runbook.targetService,
        operator: operator.trim(),
        confirm: true,
        note: note.trim() || undefined,
      });
      rememberOperator(operator.trim());
      onApproved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The approval was not accepted.');
      setSubmitting(false);
    }
  }

  return (
    <form className="approval" onSubmit={(e) => void submit(e)} aria-label={`Approve ${runbook.title}`}>
      <label>
        <span>Your name (recorded in the audit trail)</span>
        <input
          value={operator}
          onChange={(e) => setOperator(e.target.value)}
          maxLength={64}
          autoComplete="name"
          required
        />
      </label>
      <label>
        <span>Note (optional)</span>
        <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} />
      </label>
      <label className="confirm">
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        <span>
          I confirm a rolling restart of <code>{runbook.deployment}</code> in <code>{runbook.namespace}</code>.
        </span>
      </label>
      {error ? (
        <p className="notice error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="approval-actions">
        <button type="submit" className="primary" disabled={!ready}>
          {submitting ? 'Approving…' : 'Approve restart'}
        </button>
        <button type="button" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function History({ remediations }: { remediations: Remediation[] }) {
  return (
    <div className="remediation-history">
      <h3>Approved remediations</h3>
      <ol>
        {remediations.map((r) => {
          const outcome = OUTCOME[r.status];
          const reached = STEPS.findIndex((s) => s.status === r.status);
          return (
            <li key={r.id} className={outcome ? `outcome-${outcome.tone}` : 'in-progress'}>
              <p className="remediation-head">
                <strong>Restart {r.target.replace(/^deployment:/, '')}</strong>
                <span className="muted">
                  approved by {r.approvedBy}, {formatDateTime(r.approvedAt)}
                </span>
              </p>
              <ol className="steps" aria-label="Progress">
                {STEPS.map((step, i) => {
                  const done = outcome !== undefined || i < reached;
                  const current = !outcome && i === reached;
                  return (
                    <li key={step.status} className={done ? 'done' : current ? 'current' : undefined}>
                      {step.label}
                    </li>
                  );
                })}
                <li className={outcome ? `final tone-${outcome.tone}` : undefined}>
                  {outcome ? outcome.label : 'Outcome'}
                </li>
              </ol>
              <p>{r.detail}</p>
              {r.verifyUntil && r.status === 'VERIFYING' ? (
                <p className="muted">Watching the incident until {formatClock(r.verifyUntil)}.</p>
              ) : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
