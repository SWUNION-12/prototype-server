"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Center } from "@astryxdesign/core/Center";
import { Heading } from "@astryxdesign/core/Heading";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { VStack } from "@astryxdesign/core/VStack";
import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useEffect, useRef, useState } from "react";
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
  // 요청이 끝나기 전에 다시 보내면 토큰이 두 번 새로 발급되어 먼저 받은 화면이 404가 된다.
  const joining = useRef(false);

  // 이동한 뒤 뒤로 가기로 돌아오면 브라우저가 이 화면을 요청 중이던 상태(버튼 잠김) 그대로 되살릴 수 있다.
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return;
      joining.current = false;
      setBusy(false);
      setPin("");
    };
    window.addEventListener("pageshow", onShow);
    return () => window.removeEventListener("pageshow", onShow);
  }, []);

  const ready = name.trim() !== "" && code.trim() !== "" && pin.length === 4;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!ready || joining.current) return;
    joining.current = true;
    setBusy(true);
    setError(null);
    try {
      const { token, resumed } = await student.join({ entryCode: code.trim(), name: name.trim(), pin });
      // 뒤로 가기로 이 화면이 되살아나도 PIN이 남아 있지 않게 비운다. 이동하는 동안 버튼은 계속 잠가 둔다.
      setPin("");
      router.replace(`/lab?t=${encodeURIComponent(token)}${resumed ? "&resumed=1" : ""}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "입장하지 못했습니다.");
      // 틀린 PIN을 그대로 두면 같은 값으로 다시 눌러 실패 횟수만 쌓는다.
      if (err instanceof ApiError && err.status === 403) setPin("");
      joining.current = false;
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
        label="수업 코드"
        size="lg"
        value={code}
        onChange={(v) => setCode(v.toUpperCase())}
        placeholder="수업 코드를 입력해주세요"
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
        description="처음 입장하면 PIN을 새로 정하고, 다시 들어올 때는 처음 정한 PIN을 입력하세요."
        autoComplete="off"
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
          입장하는 중입니다. 클라우드 환경을 만드는 데 1분 넘게 걸릴 수 있어요. 이 화면을 닫지 말고 기다려 주세요.
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
          <Suspense fallback={<Spinner label="불러오는 중" />}>
            <JoinForm />
          </Suspense>
        </Card>
      </VStack>
    </Center>
  );
}
