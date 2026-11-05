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
  FAILED: { label: "만들기 실패", variant: "error" },
  LOCKING: { label: "준비 중", variant: "warning" },
  BUILDING: { label: "준비 중", variant: "warning" },
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

export const TRIGGER_LABEL: Record<string, string> = {
  CHECK: "점검",
  HELP: "도움 요청",
};

export const OUTCOME_LABEL: Record<string, string> = {
  RESOLVED: "해결",
  EXPLAINED: "설명",
  ESCALATED: "강사 전달",
  NO_ACTION: "조치 없음",
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

/** 기동 시간. 1초 미만은 ms, 그 이상은 초로 보인다. */
export function formatStartup(ms: number): string {
  return ms < 1000 ? `기동 ${ms} ms` : `기동 ${(ms / 1000).toFixed(1)}초`;
}

/** AI 조치 보고 칸에 보이는 글자 수 상한(공백 포함). 서버 ReportText.MAX와 같은 값이다. */
export const REPORT_MAX = 50;

/** 서버 규칙과 같다: 50자를 넘으면 앞 49자 + "…". 이 제한 이전에 저장된 행도 같게 보이도록 화면에서 한 번 더 자른다. */
export function clampReport(text: string | null): string | null {
  if (text === null) return null;
  const chars = Array.from(text);
  return chars.length <= REPORT_MAX ? text : `${chars.slice(0, REPORT_MAX - 1).join("")}…`;
}

/** AI 조치 보고 칸. 강사가 봐야 할 이유가 있으면 그것을, 없으면 최근 조치를 보여 준다. */
export function latestReport(s: StudentRow): { text: string | null; at: string | null } {
  const last = s.recentActions[0];
  if (s.needsHelp && s.needsHelpReason) {
    return { text: clampReport(s.needsHelpReason), at: last?.at ?? null };
  }
  return { text: clampReport(last?.summary ?? null), at: last?.at ?? null };
}

/** 서버는 설명을 "의미\n\n확인할 곳: 위치" 한 문자열로 준다. */
export function splitExplanation(explanation: string | null): { meaning: string; where: string | null } {
  if (!explanation) return { meaning: "", where: null };
  const marker = "\n\n확인할 곳: ";
  const at = explanation.lastIndexOf(marker);
  if (at < 0) return { meaning: explanation, where: null };
  return { meaning: explanation.slice(0, at), where: explanation.slice(at + marker.length) };
}

/** 재입장했다는 표시를 입장 화면에서 실습 화면으로 넘기는 탭 저장소 키. */
export const RESUMED_KEY = "lab-resumed";
