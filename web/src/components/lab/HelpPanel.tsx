"use client";

import { Badge } from "@astryxdesign/core/Badge";
import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Divider } from "@astryxdesign/core/Divider";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Text } from "@astryxdesign/core/Text";
import { TextArea } from "@astryxdesign/core/TextArea";
import { VStack } from "@astryxdesign/core/VStack";
import { useState } from "react";
import { ApiError, student } from "@/lib/api";
import { CLASSIFICATION_LABEL, splitExplanation } from "@/lib/display";
import type { LatestHelp } from "@/lib/types";

type Props = {
  latestHelp: LatestHelp | null;
  isCourseOpen: boolean;
  onChange: () => Promise<void>;
};

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <VStack role="status" paddingBlock={8} gap={2} hAlign="center" style={{ textAlign: "center" }}>
      {children}
    </VStack>
  );
}

function EscalateAction({ onChange }: { onChange: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function escalate() {
    setBusy(true);
    setError(null);
    try {
      await student.escalate();
      await onChange();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "강사에게 알리지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error && <Banner status="error" title={error} />}
      <Button size="lg" label="강사 호출" width="100%" isLoading={busy} onClick={escalate} />
    </>
  );
}

export function HelpPanel({ latestHelp, isCourseOpen, onChange }: Props) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // "다시 요청하기"를 누르면 그때까지의 결과는 접어 둔다. 새 요청이 처리되면 다시 결과를 보여 준다.
  const [dismissedHelpId, setDismissedHelpId] = useState<number | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await student.help(text.trim() === "" ? null : text);
      setText("");
      await onChange();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "도움을 요청하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  const help = latestHelp && latestHelp.id !== dismissedHelpId ? latestHelp : null;
  const again = () => latestHelp && setDismissedHelpId(latestHelp.id);

  if (!isCourseOpen && !help) {
    return <Text color="secondary">수업이 종료되어 도움을 요청할 수 없어요.</Text>;
  }

  if (!help) {
    return (
      <VStack gap={4}>
        {error && <Banner status="error" title={error} />}
        <TextArea
          label="오류 내용"
          isOptional
          rows={8}
          placeholder="터미널에 나온 오류를 붙여넣어 주세요"
          value={text}
          onChange={setText}
          hasSpellCheck={false}
        />
        <Text type="supporting">
          환경 문제는 바로 복구하고, 코드 오류는 정답 없이 원인을 설명해 드려요.
        </Text>
        <Button variant="primary" size="lg" label="도움 요청" width="100%" isLoading={busy} onClick={submit} />
      </VStack>
    );
  }

  if (help.status === "PROCESSING") {
    return (
      <Centered>
        <Spinner size="xl" label="환경과 오류를 확인하고 있어요" />
      </Centered>
    );
  }

  if (help.escalated || help.status === "ESCALATED") {
    return (
      <Centered>
        <Icon icon="success" color="success" size="lg" />
        <Text weight="semibold">강사에게 알렸어요</Text>
        <Text type="supporting">강사가 확인할 때까지 실습을 계속해도 돼요.</Text>
        {isCourseOpen && <Button label="새 요청" onClick={again} />}
      </Centered>
    );
  }

  if (help.status === "RESOLVED") {
    return (
      <Centered>
        <Icon icon="success" color="success" size="lg" />
        <Text weight="semibold">환경을 복구했어요</Text>
        <Text type="supporting">수업 환경과 같은 상태로 되돌렸어요. 다시 실행해 보세요.</Text>
        <VStack gap={2} width="100%" paddingBlockStart={2}>
          {isCourseOpen && <EscalateAction onChange={onChange} />}
          {isCourseOpen && <Button variant="ghost" label="다시 요청하기" onClick={again} />}
        </VStack>
      </Centered>
    );
  }

  if (help.status === "NO_ACTION") {
    return (
      <Centered>
        <Text weight="semibold">원인을 찾지 못했어요</Text>
        <Text type="supporting">오류 내용을 붙여넣어 다시 요청하거나 강사를 호출해 주세요.</Text>
        <VStack gap={2} width="100%" paddingBlockStart={2}>
          {isCourseOpen && <EscalateAction onChange={onChange} />}
          {isCourseOpen && <Button variant="ghost" label="다시 요청하기" onClick={again} />}
        </VStack>
      </Centered>
    );
  }

  // EXPLAINED
  const { meaning, where } = splitExplanation(help.explanation);
  const category = help.classification ? CLASSIFICATION_LABEL[help.classification] : undefined;
  return (
    <VStack gap={4}>
      {(category || help.errorType) && (
        <HStack gap={2} vAlign="center">
          {category && <Badge label={category} />}
          {help.errorType && (
            <Text type="code" weight="semibold">
              {help.errorType}
            </Text>
          )}
        </HStack>
      )}
      <Text as="p" className="pre-wrap">
        {meaning}
      </Text>
      {where && (
        <VStack className="look-here" gap={0.5}>
          <Text type="supporting">확인할 곳</Text>
          <Text type="code" className="pre-wrap">
            {where}
          </Text>
        </VStack>
      )}
      <Divider />
      <VStack gap={2}>
        <Text type="supporting">해결되지 않으면 강사에게 알릴 수 있어요.</Text>
        {isCourseOpen && <EscalateAction onChange={onChange} />}
        {isCourseOpen && <Button variant="ghost" label="다시 요청하기" onClick={again} />}
      </VStack>
    </VStack>
  );
}
