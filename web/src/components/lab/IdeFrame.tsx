"use client";

import { Button } from "@astryxdesign/core/Button";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { Text } from "@astryxdesign/core/Text";
import { useEffect, useRef, useState } from "react";
import { copyText } from "@/lib/clipboard";

/**
 * code-server를 화면 안에 띄운다. 로그인 폼을 대신 보내지 않는다. iframe 안에는 code-server 자체
 * 로그인 화면이 뜨고, 학생이 머리말의 비밀번호를 복사해 한 번 붙여 넣는다.
 * key={ideUrl}로 주소가 바뀌면(강사 재시작) iframe을 새로 띄운다.
 */
export function IdeFrame({ ideUrl, password }: { ideUrl: string; password: string }) {
  const [copyState, setCopyState] = useState<"idle" | "done" | "failed">("idle");
  const field = useRef<HTMLInputElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // 복사에 실패하면 값을 직접 보이고 선택해 둔다. 학생이 Ctrl+C로 복사한다.
  useEffect(() => {
    if (copyState === "failed") field.current?.select();
  }, [copyState]);

  useEffect(() => () => clearTimeout(timer.current), []);

  async function copy() {
    const ok = await copyText(password);
    setCopyState(ok ? "done" : "failed");
    clearTimeout(timer.current);
    if (ok) timer.current = setTimeout(() => setCopyState("idle"), 2500);
  }

  return (
    <>
      <div className="lab-ide-bar">
        <HStack gap={3} vAlign="center" wrap="wrap">
          <Text type="supporting">IDE 비밀번호</Text>
          {copyState === "failed" && (
            <input
              ref={field}
              className="lab-pw"
              readOnly
              value={password}
              aria-label="IDE 비밀번호"
              onFocus={(e) => e.currentTarget.select()}
            />
          )}
          <Button
            size="sm"
            variant="secondary"
            label={copyState === "done" ? "복사됨" : "복사"}
            onClick={copy}
          />
          {copyState === "failed" && (
            <Text type="supporting" role="status">
              복사하지 못했습니다. 선택한 값을 Ctrl+C(Mac은 Cmd+C)로 복사하세요.
            </Text>
          )}
        </HStack>
        <Button
          size="sm"
          variant="ghost"
          label="새 탭에서 열기"
          icon={<Icon icon="externalLink" size="sm" />}
          href={ideUrl}
          target="_blank"
          rel="noopener noreferrer"
        />
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
