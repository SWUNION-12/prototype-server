"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Center } from "@astryxdesign/core/Center";
import { EmptyState } from "@astryxdesign/core/EmptyState";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { IconButton } from "@astryxdesign/core/IconButton";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Text } from "@astryxdesign/core/Text";
import { Tooltip } from "@astryxdesign/core/Tooltip";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useState } from "react";
import { HelpIcon, LaptopIcon } from "@/components/icons";
import { HelpPanel } from "@/components/lab/HelpPanel";
import { IdeFrame } from "@/components/lab/IdeFrame";
import { LocalPanel } from "@/components/lab/LocalPanel";
import { EnvBadge } from "@/components/StatusBadge";
import { ApiError, student } from "@/lib/api";
import { describeIssue, SERVICE_NAME } from "@/lib/display";
import { usePoll } from "@/lib/use-poll";

type Panel = "help" | "local" | null;

const PANEL_TITLE = { help: "도움 요청", local: "내 PC로 이어가기" } as const;

function Lost() {
  return (
    <Center className="app-root" padding={4}>
      <EmptyState
        title="입장 정보를 찾을 수 없습니다."
        description="초대 코드와 PIN으로 다시 입장해 주세요."
        actions={<Button variant="primary" label="입장 페이지로" href="/join" />}
      />
    </Center>
  );
}

// 정적 빌드에서는 경로 값을 쓸 수 없어 토큰을 쿼리(/lab?t=...)로 받는다.
function Lab({ token }: { token: string }) {
  const fetcher = useCallback(() => student.me(token), [token]);
  const { data: me, error, refresh } = usePoll(
    fetcher,
    5000,
    (e) => e instanceof ApiError && e.status === 404,
  );
  const [panel, setPanel] = useState<Panel>(null);

  if (error instanceof ApiError && error.status === 404) return <Lost />;

  if (!me) {
    return (
      <Center className="app-root">
        {error != null ? (
          <Banner status="error" title="실습 정보를 불러오지 못했습니다. 다시 시도하고 있어요." />
        ) : (
          <Spinner size="lg" label="불러오는 중" />
        )}
      </Center>
    );
  }

  const isEnded = me.courseStatus === "ENDED";
  const isIdeUrlSafe = /^https?:\/\/\S+\/$/.test(me.ideUrl);
  const issues = me.issues.map(describeIssue).join("\n");
  const toggle = (next: Exclude<Panel, null>) => setPanel(panel === next ? null : next);
  const badge = <EnvBadge status={me.envStatus} prefix="환경" />;

  return (
    <div className="lab">
      <header className="lab-header">
        <HStack gap={3} vAlign="center" wrap="wrap">
          <Text type="large" weight="semibold">
            {SERVICE_NAME}
          </Text>
          <span className="lab-header-divider" aria-hidden />
          <Text weight="medium">{me.courseName}</Text>
          <Text type="supporting">{me.name}</Text>
        </HStack>
        <HStack gap={3} vAlign="center" wrap="wrap">
          {issues ? <Tooltip content={issues}>{badge}</Tooltip> : badge}
          <Button
            variant={panel === "local" ? "secondary" : "ghost"}
            label="내 PC로 이어가기"
            icon={<Icon icon={LaptopIcon} size="sm" />}
            onClick={() => toggle("local")}
          />
          <Button
            variant={panel === "help" ? "secondary" : "primary"}
            label="도움 요청"
            icon={<Icon icon={HelpIcon} size="sm" />}
            onClick={() => toggle("help")}
          />
        </HStack>
      </header>

      {error != null && (
        <Banner container="section" status="warning" title="서버에 연결하지 못했습니다. 다시 시도하고 있어요." />
      )}

      <div className="lab-body">
        <section className="lab-ide" aria-label="실습 IDE">
          {isEnded ? (
            <Center width="100%" className="app-root">
              <EmptyState
                title="수업이 종료되었습니다"
                description="작성한 코드는 남아 있어요. 내 PC로 이어가기에서 내려받을 수 있어요."
              />
            </Center>
          ) : isIdeUrlSafe ? (
            <IdeFrame ideUrl={me.ideUrl} password={me.idePassword} />
          ) : (
            <Center width="100%" className="app-root">
              <Banner status="error" title="IDE 주소를 확인할 수 없습니다. 강사에게 알려 주세요." />
            </Center>
          )}
        </section>

        {panel && (
          <aside className="lab-panel" aria-label={PANEL_TITLE[panel]}>
            <div className="lab-panel-header">
              <Heading level={2}>{PANEL_TITLE[panel]}</Heading>
              <IconButton
                variant="ghost"
                label="패널 닫기"
                icon={<Icon icon="close" size="sm" />}
                onClick={() => setPanel(null)}
              />
            </div>
            <div className="lab-panel-body">
              {panel === "help" ? (
                <HelpPanel
                  token={token}
                  latestHelp={me.latestHelp}
                  isCourseOpen={!isEnded}
                  onChange={refresh}
                />
              ) : (
                <LocalPanel token={token} />
              )}
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}

function LabEntry() {
  const token = useSearchParams().get("t");
  return token ? <Lab token={token} /> : <Lost />;
}

export default function LabPage() {
  return (
    <Suspense>
      <LabEntry />
    </Suspense>
  );
}
