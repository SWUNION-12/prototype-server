"use client";

import { useEffect, useRef, useState } from "react";
import { ExternalIcon } from "@/components/icons";
import { copyText } from "@/lib/clipboard";

/**
 * code-server를 화면 안에 띄운다. iframe 안에는 code-server 로그인 화면이 뜨고, 학생이 비밀번호를 복사해 한 번 붙여 넣는다.
 * 비밀번호는 password 방식 세션에만 온다. 없으면(proxy 방식) 서버가 이미 접근을 확인하므로 iframe만 둔다.
 */
export function IdeFrame({ ideUrl, password }: { ideUrl: string; password?: string }) {
  const [copyState, setCopyState] = useState<"idle" | "done" | "failed">("idle");
  const field = useRef<HTMLInputElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // 복사에 실패하면 값을 직접 보이고 선택해 둔다.
  useEffect(() => {
    if (copyState === "failed") field.current?.select();
  }, [copyState]);

  useEffect(() => () => clearTimeout(timer.current), []);

  // 비밀번호나 주소가 바뀌면(강사 재시작) 이전 복사 결과는 더 이상 맞지 않는다.
  const [seen, setSeen] = useState({ password, ideUrl });
  if (seen.password !== password || seen.ideUrl !== ideUrl) {
    setSeen({ password, ideUrl });
    setCopyState("idle");
  }

  async function copy() {
    if (!password) return;
    const ok = await copyText(password);
    setCopyState(ok ? "done" : "failed");
    clearTimeout(timer.current);
    if (ok) timer.current = setTimeout(() => setCopyState("idle"), 2500);
  }

  return (
    <>
      <div className="ide-bar">
        {password && (
          <>
            IDE 비밀번호
            {copyState === "failed" && (
              <input
                ref={field}
                className="ide-pw"
                readOnly
                value={password}
                aria-label="IDE 비밀번호"
                onFocus={(e) => e.currentTarget.select()}
              />
            )}
            <button type="button" className="btn btn--sm" onClick={copy}>
              복사
            </button>
            <span role="status">
              {copyState === "done"
                ? "복사됨"
                : copyState === "failed"
                  ? "복사하지 못했습니다. 선택한 값을 Ctrl+C(Mac은 Cmd+C)로 복사하세요."
                  : ""}
            </span>
          </>
        )}
        <a href={ideUrl} target="_blank" rel="noopener noreferrer">
          <ExternalIcon />새 탭에서 열기
        </a>
      </div>
      <iframe
        key={ideUrl}
        src={ideUrl}
        title="실습 IDE (code-server)"
        allow="clipboard-read; clipboard-write"
        referrerPolicy="no-referrer"
      />
    </>
  );
}
