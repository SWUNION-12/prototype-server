// 서버 응답 타입. 서버의 Java 뷰(StudentStatusView, DashboardView, CourseController의 응답)와 같이 고친다.

export type CourseStatus = "LOCKING" | "BUILDING" | "READY" | "FAILED" | "ENDED";
export type EnvStatus = "UNKNOWN" | "OK" | "MISMATCH" | "REPAIRING";
export type HelpStatus = "PROCESSING" | "EXPLAINED" | "RESOLVED" | "ESCALATED" | "NO_ACTION";
export type Classification = "ENV" | "CODE" | "OUT_OF_SCOPE" | "UNKNOWN";
export type LocalStatus = "NONE" | "DIAGNOSED" | "OK" | "FAIL";
export type Os = "macos" | "windows";

export type Course = {
  id: number;
  name: string;
  pythonVersion: string;
  packages: string[];
  status: CourseStatus;
  errorSummary: string | null;
  entryCode: string | null;
  createdAt: string | null;
  studentCount: number;
};

export type LocalView = {
  status: LocalStatus;
  os: string | null;
  arch: string | null;
  python: string | null;
  failed: string[];
};

export type ActionView = {
  at: string;
  trigger: string;
  classification: Classification | null;
  tools: string[];
  outcome: string | null;
  summary: string | null;
};

export type StudentRow = {
  sessionId: number;
  name: string;
  containerRunning: boolean;
  ideConnected: boolean;
  lastSeenAt: string | null;
  envStatus: EnvStatus | null;
  issues: string[];
  extras: string[];
  local: LocalView;
  needsHelp: boolean;
  needsHelpReason: string | null;
  recentActions: ActionView[];
  startupMillis: number | null;
  // PIN 값은 서버가 주지 않는다. 설정 여부만 안다.
  pinSet: boolean;
};

export type ErrorCount = { errorType: string; count: number; students: string[] };

export type Dashboard = {
  course: Pick<
    Course,
    "id" | "name" | "pythonVersion" | "packages" | "status" | "entryCode" | "errorSummary"
  >;
  students: StudentRow[];
  repeatedErrors: ErrorCount[];
  repeatedErrorsNote: string;
};

export type ResumeResult = {
  started: number;
  unhealthy: number;
  recreatedEmpty: number;
  failed: number;
};

export type DeleteResult = {
  removedContainers: number;
  removedVolumes: number;
  failures: string[];
};

export type LatestHelp = {
  id: number;
  status: HelpStatus;
  errorType: string | null;
  classification: Classification | null;
  explanation: string | null;
  escalated: boolean;
  createdAt: string;
};

export type Me = {
  name: string;
  courseName: string;
  courseStatus: CourseStatus;
  // 컨테이너에 포트 기록이 없으면(만드는 중, 멈춤) null이다.
  ideUrl: string | null;
  // password 방식 세션에만 있다. 없으면(proxy 방식) 비밀번호 칸을 보이지 않는다.
  idePassword?: string;
  envStatus: EnvStatus;
  // issues는 점검이 저장한 원본이라 모양을 믿지 않는다. 화면에는 서버가 풀어 준 issuesText를 쓴다.
  issues: unknown[];
  issuesText: string[];
  needsHelp: boolean;
  workspaceLost: boolean;
  latestHelp: LatestHelp | null;
};

export type InstallInfo = {
  diagnoseCommand: string;
  installCommand: string;
  scriptUrl: string;
  zipUrl: string;
  // 다운로드 코드가 끝나는 시각(ISO 8601). 10분 동안만 쓸 수 있다.
  expiresAt: string;
};

export type VerifyResult = {
  ok: boolean;
  python: string | null;
  passed: number | null;
  total: number | null;
  failed: string[];
  message: string;
};
