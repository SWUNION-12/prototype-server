"use client";

import { useEffect, useRef } from "react";

const FRAME_NAME = "lab-ide";

/**
 * code-server를 화면 안에 띄운다. 학생이 비밀번호를 입력하지 않도록 로그인 폼을 iframe으로 대신 전송한다.
 * 재시작이나 PIN 초기화로 주소나 비밀번호가 바뀌면 다시 로그인한다.
 */
export function IdeFrame({ ideUrl, password }: { ideUrl: string; password: string }) {
  const form = useRef<HTMLFormElement>(null);

  useEffect(() => {
    form.current?.submit();
  }, [ideUrl, password]);

  return (
    <>
      <form
        ref={form}
        className="auto-login"
        method="post"
        action={`${ideUrl}login`}
        target={FRAME_NAME}
        aria-hidden
      >
        <input type="hidden" name="password" value={password} readOnly />
      </form>
      <iframe
        name={FRAME_NAME}
        title="실습 IDE (code-server)"
        allow="clipboard-read; clipboard-write"
        referrerPolicy="no-referrer"
      />
    </>
  );
}
