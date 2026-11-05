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

type Options = { body?: unknown };

async function request<T>(method: string, url: string, { body }: Options = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
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

// 강사 계정은 브라우저 로그인 창이 받아 같은 주소의 요청에 알아서 붙인다. 화면 코드는 계정을 만지지 않는다.
// 401은 이미 열린 화면에서 계정이 사라졌을 때뿐이라, 이동하지 않고 새로고침 안내만 한다.
function instructor<T>(method: string, url: string, body?: unknown): Promise<T> {
  return request<T>(method, url, { body }).catch((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      throw new ApiError(401, "강사 로그인이 필요합니다. 페이지를 새로고침해 로그인해 주세요.");
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

const s = (token: string) => `/api/s/${encodeURIComponent(token)}`;

export const student = {
  join: (input: { entryCode: string; name: string; pin: string }) =>
    request<{ token: string; resumed: boolean }>("POST", "/api/join", { body: input }),
  me: (token: string) => request<Me>("GET", s(token)),
  help: (token: string, errorText: string | null) =>
    request<{ helpId: number }>("POST", `${s(token)}/help`, { body: { errorText } }),
  escalate: (token: string) => request<void>("POST", `${s(token)}/escalate`),
  installInfo: (token: string, os: Os) =>
    request<InstallInfo>("GET", `${s(token)}/local/install?os=${os}`),
  diagnostics: (token: string, os: Os, text: string) =>
    request<void>("POST", `${s(token)}/local/diagnostics`, { body: { os, text } }),
  verify: (token: string, text: string) =>
    request<VerifyResult>("POST", `${s(token)}/local/verify`, { body: { text } }),
};
