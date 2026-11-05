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
import { Suspense, useCallback, useEffect, useState } from "react";
import { HelpIcon, LaptopIcon } from "@/components/icons";
import { HelpPanel } from "@/components/lab/HelpPanel";
import { IdeFrame } from "@/components/lab/IdeFrame";
import { LocalPanel } from "@/components/lab/LocalPanel";
import { EnvBadge } from "@/components/StatusBadge";
import { ApiError, student } from "@/lib/api";
import { SERVICE_NAME } from "@/lib/display";
import { usePoll } from "@/lib/use-poll";

type Panel = "help" | "local" | null;

const PANEL_TITLE = { help: "도움 요청", local: "내 PC로 이어가기" } as const;

// 토큰이 없거나(주소가 잘못됨) 404(재입장으로 토큰이 바뀐 옛 탭)이면 조회를 멈추고 이 화면을 보인다.
function Lost() {
  return (
    <Center className="app-root" padding={4}>
      <EmptyState
        title="입장 정보를 찾을 수 없습니다."
        description="다른 곳에서 다시 입장했거나 주소가 잘못되었어요. 입장 코드, 이름, PIN으로 다시 입장해 주세요."
        actions={<Button variant="primary" label="입장 페이지로" href="/join" />}
      />
    </Center>
  );
}

// 정적 빌드에서는 경로 값을 쓸 수 없어 토큰을 쿼리(/lab?t=...)로 받는다.
function Lab({ token, resumed }: { token: string; resumed: boolean }) {
  const fetcher = useCallback(() => student.me(token), [token]);
  const { data: me, error, refresh } = usePoll(
    fetcher,
    5000,
    (e) => e instanceof ApiError && e.status === 404,
  );
  const [panel, setPanel] = useState<Panel>(null);
  const [resumedDismissed, setResumedDismissed] = useState(false);

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
  const zipUrl = `/l/${encodeURIComponent(token)}/code.zip`;
  const isIdeUrlSafe = /^https?:\/\/\S+\/$/.test(me.ideUrl);
  // 서버가 이미 한국어 문장으로 풀어 준다.
  const issues = me.issuesText.join("\n");
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
          {issues ? (
            <Tooltip content={<Text className="pre-wrap">{issues}</Text>}>{badge}</Tooltip>
          ) : (
            badge
          )}
          <Button
            variant={panel === "local" ? "secondary" : "ghost"}
            label="내 PC로 이어가기"
            icon={<Icon icon={LaptopIcon} size="sm" />}
            onClick={() => toggle("local")}
          />
          <Button
            variant={panel === "help" ? "secondary" : "primary"}
            label="도움 요청"
            isDisabled={isEnded && panel !== "help"}
            icon={<Icon icon={HelpIcon} size="sm" />}
            onClick={() => toggle("help")}
          />
        </HStack>
      </header>

      {error != null && (
        <Banner container="section" status="warning" title="서버에 연결하지 못했습니다. 다시 시도하고 있어요." />
      )}
      {resumed && !resumedDismissed && (
        <Banner
          container="section"
          status="success"
          title="이전 작업 공간으로 돌아왔습니다."
          isDismissable
          onDismiss={() => setResumedDismissed(true)}
        />
      )}
      {me.workspaceLost && (
        <Banner
          container="section"
          status="warning"
          title="이전 작업 공간을 찾지 못해 새로 만들었습니다."
          description="이전에 작성한 코드는 복구되지 않았어요. 필요하면 강사에게 알려 주세요."
        />
      )}

      <div className="lab-body">
        <section className="lab-ide" aria-label="실습 IDE">
          {isEnded ? (
            <Center width="100%" className="lab-ide-state" padding={4}>
              <EmptyState
                title="수업이 종료되었습니다"
                description="작성한 코드는 남아 있어요. 아래 버튼으로 내려받을 수 있어요."
                actions={<Button variant="primary" label="코드 zip 다운로드" href={zipUrl} />}
              />
            </Center>
          ) : isIdeUrlSafe ? (
            <IdeFrame ideUrl={me.ideUrl} password={me.idePassword} />
          ) : (
            <Center width="100%" className="lab-ide-state" padding={4}>
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
  const params = useSearchParams();
  const token = params.get("t");
  // 재입장 안내는 한 번만 보인다. 새로고침해도 다시 뜨지 않게 주소에서 지운다(이미 읽은 값은 그대로 쓴다).
  const [resumed] = useState(() => params.get("resumed") === "1");
  useEffect(() => {
    if (!resumed) return;
    const url = new URL(window.location.href);
    url.searchParams.delete("resumed");
    window.history.replaceState(null, "", url);
  }, [resumed]);
  return token ? <Lab key={token} token={token} resumed={resumed} /> : <Lost />;
}

export default function LabPage() {
  return (
    <Suspense
      fallback={
        <Center className="app-root">
          <Spinner size="lg" label="불러오는 중" />
        </Center>
      }
    >
      <LabEntry />
    </Suspense>
  );
}
