import type {
  Course,
  Dashboard,
  DeleteResult,
  InstallInfo,
  Me,
  Os,
  ResumeResult,
  VerifyResult,
} from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

type Options = {
  /** JSON으로 보낼 본문 */
  body?: unknown;
  /** 폼(application/x-www-form-urlencoded)으로 보낼 값. 로그인만 쓴다. */
  form?: Record<string, string>;
};

// 서버가 응답마다 심어 두는 CSRF 쿠키(HttpOnly 아님). 상태를 바꾸는 요청은 이 값을 헤더로 되돌려 보내야 한다.
// 다른 사이트는 이 쿠키를 읽지 못하므로 헤더를 만들 수 없다. 로그인하면 값이 바뀌므로 요청할 때마다 새로 읽는다.
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function csrfToken(): string | null {
  for (const part of document.cookie.split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim() === CSRF_COOKIE) return part.slice(eq + 1).trim();
  }
  return null;
}

async function request<T>(method: string, url: string, { body, form }: Options = {}): Promise<T> {
  const headers: Record<string, string> = {};
  let payload: BodyInit | undefined;
  if (form !== undefined) {
    payload = new URLSearchParams(form);
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  if (UNSAFE_METHODS.has(method)) {
    const token = csrfToken();
    if (token) headers[CSRF_HEADER] = token;
  }

  let res: Response;
  try {
    res = await fetch(url, { method, headers, body: payload });
  } catch {
    throw new ApiError(0, "서버에 연결하지 못했습니다.");
  }

  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    const error = (data as { error?: unknown } | null)?.error;
    throw new ApiError(
      res.status,
      typeof error === "string" && error ? error : `요청에 실패했습니다. (HTTP ${res.status})`,
    );
  }
  return data as T;
}

// ---- 강사 ----

export const INSTRUCTOR_HOME = "/instructor";
export const LOGIN_PATH = "/instructor/login";

// 강사 로그인은 서버 세션(HttpOnly 쿠키)이다. 화면 코드는 계정을 저장하지 않는다.
export const auth = {
  /** 로그인돼 있으면 계정 이름, 아니면 401. 첫 응답에서 CSRF 쿠키도 받는다. */
  me: () => request<{ username: string }>("GET", "/api/auth/me"),
  login: (username: string, password: string) =>
    request<void>("POST", "/api/auth/login", { form: { username, password } }),
  logout: () => request<void>("POST", "/api/auth/logout"),
};

let movingToLogin = false;

// 화면을 연 채 세션이 끝나면(8시간 동안 요청이 없거나 서버를 다시 켬) 로그인 화면으로 보내고, 로그인 뒤 이 화면으로 돌아온다.
// 여러 주기 조회가 함께 401을 받아도 한 번만 이동한다.
function moveToLogin() {
  if (movingToLogin) return;
  movingToLogin = true;
  const here = window.location.pathname + window.location.search;
  window.location.replace(`${LOGIN_PATH}?next=${encodeURIComponent(here)}`);
}

// 401이면 로그인 화면으로 옮긴다. 배너는 이동하기 전 잠깐, 또는 이동이 막혔을 때 보인다.
function instructor<T>(method: string, url: string, body?: unknown): Promise<T> {
  return request<T>(method, url, { body }).catch((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      moveToLogin();
      throw new ApiError(
        401,
        "강사 로그인이 필요합니다. 로그인 화면으로 이동합니다. 이동하지 않으면 페이지를 새로고침해 주세요.",
      );
    }
    throw e;
  });
}

export const courses = {
  list: () => instructor<Course[]>("GET", "/api/courses"),
  get: (id: number) => instructor<Course>("GET", `/api/courses/${id}`),
  create: (input: { name: string; pythonVersion: string; packages: string }) =>
    instructor<Course>("POST", "/api/courses", input),
  dashboard: (id: number) => instructor<Dashboard>("GET", `/api/courses/${id}/dashboard`),
  end: (id: number) =>
    instructor<{ status: string; stopped: number; failed: number }>("POST", `/api/courses/${id}/end`),
  resume: (id: number) => instructor<ResumeResult>("POST", `/api/courses/${id}/resume`),
  // 서버는 본문의 수업 이름이 실제 이름과 같아야 지운다.
  deleteCourse: (id: number, confirmName: string) =>
    instructor<DeleteResult>("DELETE", `/api/courses/${id}`, { confirmName }),
  resolve: (id: number, sessionId: number) =>
    instructor<void>("POST", `/api/courses/${id}/sessions/${sessionId}/resolve`),
  restart: (id: number, sessionId: number) =>
    instructor<{ ok: boolean }>("POST", `/api/courses/${id}/sessions/${sessionId}/restart`),
  resetPin: (id: number, sessionId: number) =>
    instructor<void>("POST", `/api/courses/${id}/sessions/${sessionId}/reset-pin`),
  remove: (id: number, sessionId: number) =>
    instructor<DeleteResult>("DELETE", `/api/courses/${id}/sessions/${sessionId}`),
};

// ---- 학생 ----

// 학생 세션은 HttpOnly 쿠키(LAB_STUDENT)다. 화면 코드는 토큰을 보지도 저장하지도 않는다.
// POST는 CSRF 헤더가 필요한데 쿠키는 서버의 아무 응답에서 받으므로, 첫 POST는 /api/me 조회 뒤에 보낸다.
export const student = {
  join: (input: { entryCode: string; name: string; pin: string }) =>
    request<{ resumed: boolean }>("POST", "/api/join", { body: input }),
  me: () => request<Me>("GET", "/api/me"),
  logout: () => request<void>("POST", "/api/me/logout"),
  // 터미널은 쿠키가 없어 10분짜리 다운로드 코드가 든 주소를 받는다. 부를 때마다 새로 만든다.
  localLink: (os: Os) => request<InstallInfo>("POST", `/api/me/local/link?os=${os}`),
  diagnostics: (os: Os, text: string) =>
    request<void>("POST", "/api/me/local/diagnostics", { body: { os, text } }),
  verify: (text: string) => request<VerifyResult>("POST", "/api/me/local/verify", { body: { text } }),
};

/** 화면의 코드 zip 버튼용. 쿠키로 받으므로 주소에 토큰이 없다. */
export const CODE_ZIP_URL = "/api/me/code.zip";
