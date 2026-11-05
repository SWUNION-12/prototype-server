import type { ReactNode } from "react";

type Props = {
  status?: "error" | "warning" | "success" | "info";
  title: string;
  children?: ReactNode;
  onDismiss?: () => void;
};

export function Notice({ status = "info", title, children, onDismiss }: Props) {
  return (
    <div className={`notice notice--${status}`} role={status === "error" ? "alert" : "status"}>
      <div className="notice-body">
        <b>{title}</b>
        {children}
      </div>
      {onDismiss && (
        <button type="button" className="notice-close" aria-label="닫기" onClick={onDismiss}>
          ×
        </button>
      )}
    </div>
  );
}
