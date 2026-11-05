"use client";

import { useEffect, useRef } from "react";

type Props = {
  title: string;
  description: string;
  actionLabel: string;
  isDanger?: boolean;
  isBusy: boolean;
  onAction: () => void;
  onCancel: () => void;
};

export function ConfirmDialog({ title, description, actionLabel, isDanger, isBusy, onAction, onCancel }: Props) {
  const cancel = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    cancel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !isBusy) onCancel();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [isBusy, onCancel]);

  return (
    <div className="dialog-back">
      <div className="dialog" role="alertdialog" aria-modal="true" aria-label={title}>
        <h3>{title}</h3>
        <p>{description}</p>
        <div className="dialog-foot">
          <button ref={cancel} type="button" className="btn" disabled={isBusy} onClick={onCancel}>
            취소
          </button>
          <button type="button" className={isDanger ? "btn btn--red" : "btn btn--blue"} disabled={isBusy} onClick={onAction}>
            {isBusy ? "처리 중" : actionLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
