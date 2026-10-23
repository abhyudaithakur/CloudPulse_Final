import { describe, expect, it } from 'vitest';
import { rule } from '../test/fixtures';
import { windowProgress } from './rules';

describe('windowProgress', () => {
  it('FR-03: a pending breach shows how much of the sustain window has run', () => {
    const progress = windowProgress(
      rule({ phase: 'PENDING', breachStartedAt: '2026-10-20T12:00:15Z', evaluatedAt: '2026-10-20T12:00:30Z' }),
    );

    expect(progress.text).toBe('Over threshold for 15 of 30 s');
    expect(progress.fraction).toBe(0.5);
    expect(progress.tone).toBe('caution');
  });

  it('UC-05: recovery shows progress through the recovery window', () => {
    const progress = windowProgress(
      rule({ phase: 'RECOVERING', recoveryStartedAt: '2026-10-20T12:00:25Z', evaluatedAt: '2026-10-20T12:00:30Z' }),
    );

    expect(progress.text).toBe('Back within threshold for 5 of 10 s');
    expect(progress.fraction).toBe(0.5);
  });

  it('NFR-04: unavailable telemetry has no window and an explicit label', () => {
    const progress = windowProgress(rule({ phase: 'UNAVAILABLE', value: null }));

    expect(progress.fraction).toBeNull();
    expect(progress.text).toBe('Telemetry unavailable');
    expect(progress.tone).toBe('unknown');
  });
});
