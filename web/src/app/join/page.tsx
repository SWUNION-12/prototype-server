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
import { type FormEvent, Suspense, useState } from "react";
import { ApiError, student } from "@/lib/api";
import { SERVICE_NAME } from "@/lib/display";

function JoinForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [name, setName] = useState("");
  const [code, setCode] = useState(params.get("code") ?? "");
  const [pin, setPin] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ready = name.trim() !== "" && code.trim() !== "" && pin.length === 4;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready) return;
    setBusy(true);
    setError(null);
    try {
      const { token } = await student.join({ entryCode: code.trim(), name: name.trim(), pin });
      router.replace(`/lab?t=${encodeURIComponent(token)}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "입장하지 못했습니다.");
      setBusy(false);
    }
  }

  return (
    <VStack as="form" gap={5} onSubmit={submit}>
      {error && <Banner status="error" title={error} />}
      <TextInput
        label="이름"
        size="lg"
        value={name}
        onChange={setName}
        placeholder="이름을 입력해주세요"
        autoComplete="name"
      />
      <TextInput
        label="초대 코드"
        size="lg"
        value={code}
        onChange={(v) => setCode(v.toUpperCase())}
        placeholder="초대 코드를 입력해주세요"
        autoComplete="off"
        autoCapitalize="characters"
      />
      <TextInput
        label="PIN"
        size="lg"
        type="password"
        inputMode="numeric"
        value={pin}
        onChange={(v) => setPin(v.replace(/\D/g, "").slice(0, 4))}
        placeholder="숫자 4자리를 설정해주세요"
        description="다시 입장할 때 사용합니다."
        autoComplete="new-password"
      />
      <Button
        type="submit"
        variant="primary"
        size="lg"
        label="확인"
        width="100%"
        isLoading={busy}
        isDisabled={!ready}
      />
      {busy && (
        <Text type="supporting" role="status">
          실습 환경을 준비하고 있어요. 잠시만 기다려 주세요.
        </Text>
      )}
    </VStack>
  );
}

export default function JoinPage() {
  return (
    <Center className="app-root" padding={4}>
      <VStack gap={8} width="100%" maxWidth={400}>
        <VStack hAlign="center">
          <Heading level={1}>{SERVICE_NAME}</Heading>
        </VStack>
        <Card padding={8}>
          <Suspense>
            <JoinForm />
          </Suspense>
        </Card>
      </VStack>
    </Center>
  );
}
