import { useCallback, useEffect, useRef, useState } from 'react';

export interface Polled<T> {
  data: T | undefined;
  /** Message of the latest failure; cleared by the next success. */
  error: string | undefined;
  /** Epoch ms of the last successful response. */
  updatedAt: number | undefined;
  /** True when the latest attempt failed but older data is still shown. */
  stale: boolean;
  refresh: () => void;
}

/**
 * Fetches immediately, then again `intervalMs` after each response (never overlapping). On
 * failure the previous data is kept and flagged stale, so the UI can say what it is showing
 * instead of silently presenting old numbers as current.
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  key: string = '',
): Polled<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [updatedAt, setUpdatedAt] = useState<number>();
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    setData(undefined);
    setError(undefined);
    setUpdatedAt(undefined);
  }, [key]);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    const run = async () => {
      try {
        const next = await fetcherRef.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(next);
        setError(undefined);
        setUpdatedAt(Date.now());
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : String(e));
      }
      if (!controller.signal.aborted) timer = setTimeout(run, intervalMs);
    };
    void run();

    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [intervalMs, key, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, updatedAt, stale: error !== undefined && data !== undefined, refresh };
}
