"use client";

import { createContext, type ReactNode, useCallback, useContext, useEffect, useRef, useState } from "react";

type Toast = { id: number; body: string; type: "info" | "error" };
type ShowToast = (toast: { body: string; type?: "info" | "error" }) => void;

const ToastContext = createContext<ShowToast | null>(null);
const DURATION_MS = 4000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);
  const timers = useRef(new Set<ReturnType<typeof setTimeout>>());

  useEffect(() => {
    const active = timers.current;
    return () => active.forEach(clearTimeout);
  }, []);

  const show = useCallback<ShowToast>(({ body, type = "info" }) => {
    const id = nextId.current++;
    setToasts((prev) => [...prev, { id, body, type }]);
    const timer = setTimeout(() => {
      timers.current.delete(timer);
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, DURATION_MS);
    timers.current.add(timer);
  }, []);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={t.type === "error" ? "toast toast--error" : "toast"}>
            {t.body}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ShowToast {
  const show = useContext(ToastContext);
  if (!show) throw new Error("useToast는 ToastProvider 안에서만 쓸 수 있습니다.");
  return show;
}
