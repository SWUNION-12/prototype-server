"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Center } from "@astryxdesign/core/Center";
import { Heading } from "@astryxdesign/core/Heading";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { VStack } from "@astryxdesign/core/VStack";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { ApiError, verifyLogin } from "@/lib/api";
import { setCredentials } from "@/lib/auth";
import { SERVICE_NAME } from "@/lib/display";

export default function AdminLoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const basic = await verifyLogin(username, password);
      setCredentials({ username, basic });
      router.replace("/admin");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "로그인하지 못했습니다.");
      setBusy(false);
    }
  }

  return (
    <Center className="app-root" padding={4}>
      <VStack gap={8} width="100%" maxWidth={400}>
        <VStack gap={1} hAlign="center">
          <Heading level={1}>{SERVICE_NAME}</Heading>
          <Text type="supporting">강사 로그인</Text>
        </VStack>
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
      </VStack>
    </Center>
  );
}
