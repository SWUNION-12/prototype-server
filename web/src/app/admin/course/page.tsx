"use client";

import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { Spinner } from "@astryxdesign/core/Spinner";
import { Tab, TabList } from "@astryxdesign/core/TabList";
import { Text } from "@astryxdesign/core/Text";
import { useToast } from "@astryxdesign/core/Toast";
import { VStack } from "@astryxdesign/core/VStack";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback } from "react";
import { CourseProvider } from "@/components/admin/CourseContext";
import { ProvisioningProgress } from "@/components/admin/ProvisioningProgress";
import { AppHeader } from "@/components/AppHeader";
import { ApiError, courses } from "@/lib/api";
import { studentCountLabel } from "@/lib/display";
import type { Dashboard } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { SettingsTab } from "./SettingsTab";
import { StudentsTab } from "./StudentsTab";

function EntryInfo({ entryCode }: { entryCode: string }) {
  const showToast = useToast();

  async function copy(text: string, message: string) {
    try {
      await navigator.clipboard.writeText(text);
      showToast({ body: message });
    } catch {
      showToast({ body: `복사하지 못했습니다. 직접 전달해 주세요: ${text}`, type: "error" });
    }
  }

  return (
    <HStack gap={2} vAlign="center" wrap="wrap">
      <Text type="supporting">초대 코드</Text>
      <Text type="code" size="lg" weight="semibold">
        {entryCode}
      </Text>
      <Button
        size="sm"
        label="코드 복사"
        onClick={() => copy(entryCode, "초대 코드를 복사했습니다.")}
      />
      <Button
        size="sm"
        label="입장 링크 복사"
        onClick={() =>
          copy(`${window.location.origin}/join?code=${entryCode}`, "입장 링크를 복사했습니다.")
        }
      />
    </HStack>
  );
}

function StatusBanner({ dashboard }: { dashboard: Dashboard }) {
  const { status, errorSummary } = dashboard.course;
  if (status === "LOCKING" || status === "BUILDING") {
    return (
      <ProvisioningProgress
        courseId={dashboard.course.id}
        status={status}
        packages={dashboard.course.packages}
      />
    );
  }
  if (status === "FAILED") {
    return (
      <Banner status="error" title="환경을 만들지 못했습니다" description="패키지나 버전을 바꿔 새 프로젝트를 만들어 주세요.">
        {errorSummary && <pre className="error-summary">{errorSummary}</pre>}
      </Banner>
    );
  }
  if (status === "ENDED") {
    return (
      <Banner
        status="info"
        title="종료된 수업입니다"
        description="학생 컨테이너를 멈췄습니다. 학생 코드는 남아 있습니다."
      />
    );
  }
  return null;
}

// 정적 빌드에서는 경로 값을 쓸 수 없어 수업 번호와 탭을 쿼리(?id=1&tab=settings)로 받는다.
function CourseView({ courseId, tab }: { courseId: number; tab: "students" | "settings" }) {
  const fetcher = useCallback(() => courses.dashboard(courseId), [courseId]);
  const { data, error, refresh } = usePoll(
    fetcher,
    3000,
    (e) => e instanceof ApiError && e.status === 404,
  );

  const base = `/admin/course?id=${courseId}`;

  return (
    <VStack className="app-root">
      <AppHeader />
      <VStack as="main" className="page page--detail" gap={6}>
        <VStack gap={3}>
          <HStack>
            <Button
              variant="ghost"
              size="sm"
              label="이전"
              href="/admin"
              icon={<Icon icon="chevronLeft" size="sm" />}
            />
          </HStack>
          {data && (
            <HStack hAlign="between" vAlign="center" wrap="wrap" gap={4}>
              <VStack gap={0.5}>
                <Heading level={1}>{data.course.name}</Heading>
                <Text type="supporting">
                  Python {data.course.pythonVersion} ·{" "}
                  {studentCountLabel(data.students.length, data.course.capacity)}
                </Text>
              </VStack>
              {data.course.entryCode && data.course.status === "READY" && (
                <EntryInfo entryCode={data.course.entryCode} />
              )}
            </HStack>
          )}
        </VStack>

        {data ? (
          <>
            <StatusBanner dashboard={data} />
            <TabList value={tab} onChange={() => {}} hasDivider>
              <Tab value="students" label="학생 목록" href={base} />
              <Tab value="settings" label="프로젝트 설정" href={`${base}&tab=settings`} />
            </TabList>
            <CourseProvider value={{ courseId, dashboard: data, refresh }}>{tab === "settings" ? <SettingsTab /> : <StudentsTab />}</CourseProvider>
          </>
        ) : error != null ? (
          <Banner
            status="error"
            title={
              error instanceof ApiError && error.status === 404
                ? "프로젝트를 찾을 수 없습니다."
                : error instanceof ApiError
                  ? error.message
                  : "프로젝트를 불러오지 못했습니다."
            }
          />
        ) : (
          <Spinner label="불러오는 중" />
        )}
      </VStack>
    </VStack>
  );
}

function CourseEntry() {
  const params = useSearchParams();
  const raw = params.get("id") ?? "";
  const courseId = /^\d+$/.test(raw) ? Number(raw) : null;
  if (courseId === null) {
    return (
      <VStack className="app-root">
        <AppHeader />
        <VStack as="main" className="page page--detail" gap={6}>
          <Banner
            status="error"
            title="수업 주소가 올바르지 않습니다."
            description="강사 홈에서 수업을 골라 다시 열어 주세요."
          />
          <HStack>
            <Button label="강사 홈으로" href="/admin" />
          </HStack>
        </VStack>
      </VStack>
    );
  }
  return <CourseView courseId={courseId} tab={params.get("tab") === "settings" ? "settings" : "students"} />;
}

export default function CoursePage() {
  return (
    <Suspense>
      <CourseEntry />
    </Suspense>
  );
}
