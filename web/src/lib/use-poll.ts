"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const MAX_BACKOFF_MS = 30_000;
const HIDDEN_SLOWDOWN = 4;

/**
 * 주기 조회. 앞선 요청이 끝나야 다음을 보내고, 실패하면 간격을 늘리고, 탭이 가려지면 드물게 조회한다.
 * stopOn에 해당하는 오류가 나면 더 조회하지 않는다.
 */
export function usePoll<T>(
  fetcher: () => Promise<T>,
  intervalMs: number,
  stopOn?: (error: unknown) => boolean,
) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const fetcherRef = useRef(fetcher);
  const stopOnRef = useRef(stopOn);
  useEffect(() => {
    fetcherRef.current = fetcher;
    stopOnRef.current = stopOn;
  });

  const runRef = useRef<() => Promise<void>>(async () => {});

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let pending = false;
    let rerun = false;
    let fails = 0;
    let stopped = false;

    const schedule = () => {
      clearTimeout(timer);
      if (stopped) return;
      const base = document.hidden ? intervalMs * HIDDEN_SLOWDOWN : intervalMs;
      const delay = fails === 0 ? base : Math.min(base * 2 ** fails, MAX_BACKOFF_MS);
      timer = setTimeout(run, delay);
    };

    const run = async () => {
      if (stopped) return;
      if (pending) {
        rerun = true;
        return;
      }
      pending = true;
      clearTimeout(timer);
      try {
        const next = await fetcherRef.current();
        if (stopped) return;
        fails = 0;
        setData(next);
        setError(null);
      } catch (e) {
        if (stopped) return;
        fails++;
        setError(e);
        if (stopOnRef.current?.(e)) stopped = true;
      } finally {
        pending = false;
        if (rerun && !stopped) {
          rerun = false;
          void run();
        } else {
          schedule();
        }
      }
    };

    const onVisibility = () => {
      if (!document.hidden) void run();
    };

    runRef.current = run;
    void run();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [intervalMs]);

  const refresh = useCallback(() => runRef.current(), []);

  return { data, error, refresh };
}
