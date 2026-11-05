"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Center } from "@astryxdesign/core/Center";
import { Heading } from "@astryxdesign/core/Heading";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { VStack } from "@astryxdesign/core/VStack";
import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useEffect, useRef, useState } from "react";
import { ApiError, auth, INSTRUCTOR_HOME, LOGIN_PATH } from "@/lib/api";
import { SERVICE_NAME } from "@/lib/display";

// 로그인 뒤 돌아갈 곳. 같은 사이트의 강사 화면 주소만 따르고, 아니면 강사 첫 화면으로 간다.
// 정적 빌드의 파일 주소(.html, RSC 조각 .txt)와 로그인 화면 자신은 돌아갈 화면이 아니다.
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
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 첫 확인 요청이 CSRF 쿠키를 받아 온다. 로그인 요청은 그 쿠키 값을 헤더로 보내야 하므로 확인이 끝난 뒤 보낸다.
  const checked = useRef<Promise<unknown> | null>(null);

  useEffect(() => {
    let active = true;
    const me = auth.me();
    checked.current = me.catch(() => null);
    // 이미 로그인돼 있으면 바로 돌아간다.
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
    <Card padding={8}>
      <VStack as="form" gap={5} onSubmit={submit}>
        {error && <Banner status="error" title={error} />}
        <TextInput
          label="아이디"
          size="lg"
          value={username}
          onChange={setUsername}
          placeholder="아이디를 입력해주세요"
          autoComplete="username"
        />
        <TextInput
          label="비밀번호"
          size="lg"
          type="password"
          value={password}
          onChange={setPassword}
          placeholder="비밀번호를 입력해주세요"
          autoComplete="current-password"
        />
        <Button
          type="submit"
          variant="primary"
          size="lg"
          label="로그인"
          width="100%"
          isLoading={busy}
          isDisabled={!username || !password}
        />
      </VStack>
    </Card>
  );
}

export default function InstructorLoginPage() {
  return (
    <Center className="app-root" padding={4}>
      <VStack gap={8} width="100%" maxWidth={400}>
        <VStack gap={1} hAlign="center">
          <Heading level={1}>{SERVICE_NAME}</Heading>
          <Text type="supporting">강사 로그인</Text>
        </VStack>
        <Suspense>
          <LoginForm />
        </Suspense>
      </VStack>
    </Center>
  );
}
