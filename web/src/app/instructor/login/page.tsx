"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useEffect, useRef, useState } from "react";
import { EyeIcon, EyeOffIcon, LockIcon, UserIcon } from "@/components/icons";
import { Notice } from "@/components/Notice";
import { ApiError, auth, INSTRUCTOR_HOME, LOGIN_PATH } from "@/lib/api";
import { SERVICE_NAME } from "@/lib/display";

// 로그인 뒤 돌아갈 곳. 같은 사이트의 강사 화면 주소만 따르고, 아니면 강사 첫 화면으로 간다.
function safeNext(raw: string | null): string {
  if (!raw || raw.startsWith("//")) return INSTRUCTOR_HOME;
  const path = raw.split(/[?#]/, 1)[0];
  if (path !== INSTRUCTOR_HOME && !path.startsWith(`${INSTRUCTOR_HOME}/`)) return INSTRUCTOR_HOME;
  if (path.slice(path.lastIndexOf("/") + 1).includes(".")) return INSTRUCTOR_HOME;
  if (path === LOGIN_PATH || path.startsWith(`${LOGIN_PATH}/`)) return INSTRUCTOR_HOME;
  return raw;
}

function LoginForm() {
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 첫 확인 요청이 CSRF 쿠키를 받아 온다. 로그인 요청은 그 쿠키 값을 헤더로 보내야 하므로 확인이 끝난 뒤 보낸다.
  const checked = useRef<Promise<unknown> | null>(null);

  useEffect(() => {
    let active = true;
    const me = auth.me();
    checked.current = me.catch(() => null);
    me.then(
      () => {
        if (active) router.replace(next);
      },
      () => {},
    );
    return () => {
      active = false;
    };
  }, [next, router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await checked.current;
      await auth.login(username, password);
      router.replace(next);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "로그인하지 못했습니다.");
      setBusy(false);
    }
  }

  return (
    <form className="login-box" onSubmit={submit}>
      <div className="login-logo">{SERVICE_NAME}</div>
      {error && <Notice status="error" title={error} />}
      <label className="login-field">
        <UserIcon />
        <input
          placeholder="아이디"
          aria-label="아이디"
          autoComplete="username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
      </label>
      <label className="login-field">
        <LockIcon />
        <input
          type={showPassword ? "text" : "password"}
          placeholder="비밀번호"
          aria-label="비밀번호"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button
          type="button"
          className="login-eye"
          aria-label={showPassword ? "비밀번호 숨기기" : "비밀번호 보기"}
          onClick={() => setShowPassword(!showPassword)}
        >
          {showPassword ? <EyeIcon /> : <EyeOffIcon />}
        </button>
      </label>
      <button type="submit" className="btn btn--blue btn--block login-submit" disabled={busy || !username || !password}>
        {busy ? "로그인하는 중" : "로그인"}
      </button>
    </form>
  );
}

export default function InstructorLoginPage() {
  return (
    <div className="page page--corners" style={{ paddingTop: 0 }}>
      <div className="center center--full">
        <Suspense>
          <LoginForm />
        </Suspense>
      </div>
    </div>
  );
}
