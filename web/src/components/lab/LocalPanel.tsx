"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { CodeBlock } from "@astryxdesign/core/CodeBlock";
import { Divider } from "@astryxdesign/core/Divider";
import { HStack } from "@astryxdesign/core/HStack";
import { Link } from "@astryxdesign/core/Link";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Text } from "@astryxdesign/core/Text";
import { TextArea } from "@astryxdesign/core/TextArea";
import { VStack } from "@astryxdesign/core/VStack";
import { useEffect, useState } from "react";
import { ApiError, student } from "@/lib/api";
import type { InstallInfo, Os, VerifyResult } from "@/lib/types";

// 서버가 준 주소는 http(s)나 같은 사이트 경로일 때만 링크로 쓴다.
const isSafeHref = (u: string) => /^https?:\/\/\S+$/i.test(u) || /^\/[^\s/]\S*$/.test(u);

const message = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback);

function Command({ code }: { code: string }) {
  return <CodeBlock code={code} language="bash" hasLanguageLabel={false} isWrapped width="100%" size="sm" />;
}

function Steps({ token, os, info }: { token: string; os: Os; info: InstallInfo }) {
  const [diagText, setDiagText] = useState("");
  const [diagBusy, setDiagBusy] = useState(false);
  const [diagError, setDiagError] = useState<string | null>(null);
  const [diagnosed, setDiagnosed] = useState(false);

  const [verifyText, setVerifyText] = useState("");
  const [verifyBusy, setVerifyBusy] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [result, setResult] = useState<VerifyResult | null>(null);

  async function submitDiagnostics() {
    setDiagBusy(true);
    setDiagError(null);
    try {
      await student.diagnostics(token, os, diagText);
      setDiagnosed(true);
    } catch (e) {
      setDiagError(message(e, "출력을 확인하지 못했습니다."));
    } finally {
      setDiagBusy(false);
    }
  }

  async function submitVerify() {
    setVerifyBusy(true);
    setVerifyError(null);
    setResult(null);
    try {
      setResult(await student.verify(token, verifyText));
    } catch (e) {
      setVerifyError(message(e, "검증 결과를 제출하지 못했습니다."));
    } finally {
      setVerifyBusy(false);
    }
  }

  const terminal = os === "windows" ? "PowerShell" : "터미널";

  return (
    <VStack gap={5}>
      <VStack gap={3}>
        <Text weight="semibold">1. 내 컴퓨터 환경 확인</Text>
        <Text type="supporting">{terminal}에서 실행하고, 출력된 세 줄을 붙여넣어 주세요.</Text>
        <Command code={info.diagnoseCommand} />
        <TextArea
          label="출력 결과"
          isLabelHidden
          rows={3}
          placeholder={"os=...\narch=...\npython=..."}
          value={diagText}
          onChange={setDiagText}
          hasSpellCheck={false}
          status={
            diagError
              ? { type: "error", message: diagError }
              : diagnosed
                ? { type: "success", message: "확인했어요. 이제 설치를 진행해 주세요." }
                : undefined
          }
        />
        <HStack hAlign="end">
          <Button label="확인" onClick={submitDiagnostics} isLoading={diagBusy} isDisabled={!diagText.trim()} />
        </HStack>
      </VStack>

      {diagnosed && (
        <>
          <Divider />
          <VStack gap={3}>
            <Text weight="semibold">2. 설치</Text>
            <Text type="supporting">
              {terminal}에서 실행하면 수업과 같은 환경이 설치되고 검증 결과가 출력돼요.
            </Text>
            <Command code={info.installCommand} />
            {isSafeHref(info.scriptUrl) ? (
              <Link href={info.scriptUrl} isExternalLink>
                실행 전에 스크립트 내용 보기
              </Link>
            ) : (
              <Text type="supporting">스크립트 주소를 확인할 수 없습니다. 강사에게 알려 주세요.</Text>
            )}
          </VStack>
          <Divider />
          <VStack gap={3}>
            <Text weight="semibold">3. 검증 결과 제출</Text>
            <TextArea
              label="검증 결과"
              isLabelHidden
              rows={5}
              placeholder="설치가 끝나면 출력된 내용을 붙여넣어 주세요"
              value={verifyText}
              onChange={setVerifyText}
              hasSpellCheck={false}
              status={verifyError ? { type: "error", message: verifyError } : undefined}
            />
            <HStack hAlign="end">
              <Button label="제출" onClick={submitVerify} isLoading={verifyBusy} isDisabled={!verifyText.trim()} />
            </HStack>
            {result && (
              <Banner
                status={result.ok ? "success" : "error"}
                title={result.ok ? "설치가 끝났어요" : "설치 검증에 실패했어요"}
                description={result.message}
              >
                {result.failed.length > 0 && <Text type="code">{result.failed.join(", ")}</Text>}
              </Banner>
            )}
          </VStack>
        </>
      )}
    </VStack>
  );
}

export function LocalPanel({ token }: { token: string }) {
  const [os, setOs] = useState<Os>("macos");
  const [loaded, setLoaded] = useState<{ os: Os; info: InstallInfo } | null>(null);
  const [error, setError] = useState<{ os: Os; text: string } | null>(null);

  useEffect(() => {
    let live = true;
    student
      .installInfo(token, os)
      .then((next) => {
        if (live) setLoaded({ os, info: next });
      })
      .catch((e: unknown) => {
        if (live) setError({ os, text: message(e, "설치 안내를 불러오지 못했습니다.") });
      });
    return () => {
      live = false;
    };
  }, [token, os]);

  const info = loaded?.os === os ? loaded.info : null;
  const errorText = error?.os === os ? error.text : null;

  return (
    <VStack gap={5}>
      <SegmentedControl
        label="내 컴퓨터 운영체제"
        layout="fill"
        value={os}
        onChange={(v) => setOs(v as Os)}
      >
        <SegmentedControlItem value="macos" label="macOS" />
        <SegmentedControlItem value="windows" label="Windows" />
      </SegmentedControl>

      {errorText && <Banner status="error" title={errorText} />}
      {!info && !errorText && <Spinner label="불러오는 중" />}
      {/* 운영체제를 바꾸면 붙여넣은 내용이 맞지 않으므로 처음부터 다시 받는다. */}
      {info && <Steps key={os} token={token} os={os} info={info} />}

      <Divider />
      <VStack gap={2}>
        <Text weight="semibold">내 코드 내려받기</Text>
        <Text type="supporting">클라우드에서 작성한 코드를 zip으로 받아요. 가상환경과 캐시는 빠져요.</Text>
        <Button label="코드 zip 다운로드" href={`/l/${encodeURIComponent(token)}/code.zip`} />
      </VStack>
    </VStack>
  );
}
