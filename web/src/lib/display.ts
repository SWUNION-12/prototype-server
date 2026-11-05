import type { Classification, CourseStatus, EnvStatus, StudentRow } from "./types";

export const SERVICE_NAME = "코딩책상";

export type BadgeVariant = "success" | "warning" | "error" | "info" | "neutral";

export const ENV_BADGE: Record<EnvStatus, { label: string; variant: BadgeVariant }> = {
  OK: { label: "정상", variant: "success" },
  MISMATCH: { label: "불일치", variant: "warning" },
  REPAIRING: { label: "복구 중", variant: "info" },
  UNKNOWN: { label: "확인 불가", variant: "neutral" },
};

export const COURSE_BADGE: Record<CourseStatus, { label: string; variant: BadgeVariant }> = {
  READY: { label: "정상", variant: "success" },
  FAILED: { label: "오류", variant: "error" },
  LOCKING: { label: "준비 중", variant: "info" },
  BUILDING: { label: "준비 중", variant: "info" },
  ENDED: { label: "종료됨", variant: "neutral" },
};

export const CLASSIFICATION_LABEL: Partial<Record<Classification, string>> = {
  CODE: "코드 오류",
  ENV: "환경 문제",
  OUT_OF_SCOPE: "범위 밖 문제",
};

export const LOCAL_LABEL: Record<string, string> = {
  DIAGNOSED: "내 PC 확인됨",
  OK: "내 PC 설치 완료",
  FAIL: "내 PC 설치 실패",
};

export function studentCountLabel(count: number): string {
  return `학생 ${count}명`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });
}

export function formatElapsed(totalSeconds: number): string {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}시간 ${minutes}분`;
  if (minutes > 0) return `${minutes}분 ${seconds}초`;
  return `${seconds}초`;
}

/** AI 조치 보고 칸. 강사가 봐야 할 이유가 있으면 그것을, 없으면 최근 조치를 보여 준다. */
export function latestReport(s: StudentRow): { text: string | null; at: string | null } {
  const last = s.recentActions[0];
  if (s.needsHelp && s.needsHelpReason) {
    return { text: s.needsHelpReason, at: last?.at ?? null };
  }
  return { text: last?.summary ?? null, at: last?.at ?? null };
}

/** 서버는 설명을 "의미\n\n확인할 곳: 위치" 한 문자열로 준다. */
export function splitExplanation(explanation: string | null): { meaning: string; where: string | null } {
  if (!explanation) return { meaning: "", where: null };
  const marker = "\n\n확인할 곳: ";
  const at = explanation.lastIndexOf(marker);
  if (at < 0) return { meaning: explanation, where: null };
  return { meaning: explanation.slice(0, at), where: explanation.slice(at + marker.length) };
}
