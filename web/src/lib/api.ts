import { clearCredentials, encodeBasic, getCredentials } from "./auth";
import type {
  Course,
  Dashboard,
  InstallInfo,
  Me,
  Os,
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

type Options = { body?: unknown; basic?: string };

async function request<T>(method: string, url: string, { body, basic }: Options = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (basic) {
    headers.Authorization = `Basic ${basic}`;
    // Spring Security는 이 헤더가 있으면 WWW-Authenticate를 빼서 브라우저 로그인 창을 띄우지 않는다.
    headers["X-Requested-With"] = "XMLHttpRequest";
  }

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

function instructor<T>(method: string, url: string, body?: unknown): Promise<T> {
  const basic = getCredentials()?.basic;
  return request<T>(method, url, { body, basic }).catch((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      clearCredentials();
      window.location.assign("/admin/login");
    }
    throw e;
  });
}

/** 서버에 로그인 API가 없어 강사 API를 한 번 불러 계정을 확인한다. */
export async function verifyLogin(username: string, password: string): Promise<string> {
  const basic = encodeBasic(username, password);
  try {
    await request("GET", "/api/courses", { basic });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      throw new ApiError(401, "아이디 또는 비밀번호를 확인해 주세요.");
    }
    throw e;
  }
  return basic;
}

export const courses = {
  list: () => instructor<Course[]>("GET", "/api/courses"),
  get: (id: number) => instructor<Course>("GET", `/api/courses/${id}`),
  create: (input: { name: string; pythonVersion: string; packages: string }) =>
    instructor<Course>("POST", "/api/courses", input),
  dashboard: (id: number) => instructor<Dashboard>("GET", `/api/courses/${id}/dashboard`),
  update: (id: number, patch: { name?: string; capacity?: number | null }) =>
    instructor<Course>("PATCH", `/api/courses/${id}`, patch),
  end: (id: number) =>
    instructor<{ status: string; stopped: number; failed: number }>("POST", `/api/courses/${id}/end`),
  resolve: (id: number, sessionId: number) =>
    instructor<void>("POST", `/api/courses/${id}/sessions/${sessionId}/resolve`),
  restart: (id: number, sessionId: number) =>
    instructor<void>("POST", `/api/courses/${id}/sessions/${sessionId}/restart`),
  resetPin: (id: number, sessionId: number) =>
    instructor<{ pin: string }>("POST", `/api/courses/${id}/sessions/${sessionId}/reset-pin`),
  remove: (id: number, sessionId: number) =>
    instructor<void>("DELETE", `/api/courses/${id}/sessions/${sessionId}`),
};

// ---- 학생 ----

const s = (token: string) => `/api/s/${encodeURIComponent(token)}`;

export const student = {
  join: (input: { entryCode: string; name: string; pin: string }) =>
    request<{ token: string; rejoined: boolean }>("POST", "/api/join", { body: input }),
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
