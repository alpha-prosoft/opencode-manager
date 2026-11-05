import { useCallback, useEffect, useState } from "react";

export function useLoad<T>(load: () => Promise<T>, deps: unknown[], refreshMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const reload = useCallback(() => {
    load()
      .then((value) => {
        setData(value);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, deps);

  useEffect(() => {
    reload();
    if (!refreshMs) return;
    const timer = setInterval(reload, refreshMs);
    return () => clearInterval(timer);
  }, [reload, refreshMs]);

  return { data, error, reload };
}
