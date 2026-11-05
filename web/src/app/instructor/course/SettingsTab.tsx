"use client";

import { AlertDialog } from "@astryxdesign/core/AlertDialog";
import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { MetadataList, MetadataListItem } from "@astryxdesign/core/MetadataList";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { useToast } from "@astryxdesign/core/Toast";
import { Token } from "@astryxdesign/core/Token";
import { VStack } from "@astryxdesign/core/VStack";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useCourse } from "@/components/admin/CourseContext";
import { CourseBadge } from "@/components/StatusBadge";
import { ApiError, courses } from "@/lib/api";
import type { DeleteResult, ResumeResult } from "@/lib/types";

type Busy = "end" | "resume" | "delete" | null;

function CourseInfo() {
  const { course } = useCourse().dashboard;
  return (
    <Card padding={6}>
      <VStack gap={4}>
        <VStack gap={0.5}>
          <Heading level={2}>수업 정보</Heading>
          <Text type="supporting">
            수업을 만든 뒤에는 환경을 바꿀 수 없습니다. 바꾸려면 새 수업을 만들어 주세요.
          </Text>
        </VStack>
        <MetadataList label={{ position: "start", width: 120 }}>
          <MetadataListItem label="Python">
            <Text type="code">{course.pythonVersion}</Text>
          </MetadataListItem>
          <MetadataListItem label="패키지">
            {course.packages.length === 0 ? (
              <Text type="supporting">없음</Text>
            ) : (
              <HStack gap={1.5} wrap="wrap">
                {course.packages.map((p) => (
                  <Token key={p} label={p} size="sm" />
                ))}
              </HStack>
            )}
          </MetadataListItem>
          <MetadataListItem label="입장 코드">
            {course.entryCode ? (
              <Text type="code">{course.entryCode}</Text>
            ) : (
              <Text type="supporting">없음</Text>
            )}
          </MetadataListItem>
          <MetadataListItem label="상태">
            <CourseBadge status={course.status} />
          </MetadataListItem>
        </MetadataList>
        {course.errorSummary && (
          <VStack gap={2}>
            <Text type="supporting">오류 요약</Text>
            <pre className="error-summary">{course.errorSummary}</pre>
          </VStack>
        )}
      </VStack>
    </Card>
  );
}

function EndCourse({ busy, setBusy }: { busy: Busy; setBusy: (b: Busy) => void }) {
  const { courseId, dashboard, refresh } = useCourse();
  const showToast = useToast();
  const [open, setOpen] = useState(false);

  // 서버는 READY인 수업만 종료할 수 있다.
  const enabled = dashboard.course.status === "READY" && busy === null;

  async function end() {
    setBusy("end");
    try {
      const r = await courses.end(courseId);
      showToast({
        body: `수업을 종료했습니다. 멈춘 컨테이너 ${r.stopped}개, 멈추지 못한 컨테이너 ${r.failed}개.`,
        type: r.failed > 0 ? "error" : "info",
      });
      setOpen(false);
      await refresh();
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "종료하지 못했습니다.", type: "error" });
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card padding={6}>
      <HStack hAlign="between" vAlign="center" gap={4} wrap="wrap">
        <VStack gap={0.5}>
          <Heading level={2}>수업 종료</Heading>
          <Text type="supporting">컨테이너만 멈추고 학생 볼륨(코드)은 남습니다.</Text>
        </VStack>
        <Button
          variant="destructive"
          label="수업 종료"
          isDisabled={!enabled}
          isLoading={busy === "end"}
          onClick={() => setOpen(true)}
        />
      </HStack>
      {open && (
        <AlertDialog
          isOpen
          onOpenChange={(next) => {
            if (busy !== "end") setOpen(next);
          }}
          title="수업을 종료할까요?"
          description="모든 학생의 IDE가 멈추고 새로 입장할 수 없습니다. 학생 코드는 남고, 종료한 수업은 다시 열 수 있습니다."
          actionLabel="수업 종료"
          cancelLabel="취소"
          isActionLoading={busy === "end"}
          onAction={end}
        />
      )}
    </Card>
  );
}

function ResumeCourse({ busy, setBusy }: { busy: Busy; setBusy: (b: Busy) => void }) {
  const { courseId, dashboard, refresh } = useCourse();
  const [result, setResult] = useState<ResumeResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 종료된 수업만 다시 열 수 있다.
  const enabled = dashboard.course.status === "ENDED" && busy === null;

  async function resume() {
    setBusy("resume");
    setResult(null);
    setError(null);
    try {
      setResult(await courses.resume(courseId));
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "재개하지 못했습니다.");
    } finally {
      setBusy(null);
    }
  }

  const trouble = result ? result.unhealthy + result.recreatedEmpty + result.failed > 0 : false;

  return (
    <Card padding={6}>
      <VStack gap={4}>
        <HStack hAlign="between" vAlign="center" gap={4} wrap="wrap">
          <VStack gap={0.5}>
            <Heading level={2}>수업 재개</Heading>
            <Text type="supporting">
              종료된 수업만 다시 엽니다. 학생 컨테이너를 켜고 IDE가 응답할 때까지 기다리므로 최대 1분쯤 걸립니다. 학생 코드는 그대로 이어집니다.
            </Text>
          </VStack>
          <Button
            label="수업 재개"
            isDisabled={!enabled}
            isLoading={busy === "resume"}
            onClick={resume}
          />
        </HStack>
        {busy === "resume" && (
          <Text type="supporting">
            재개하는 중입니다. 학생 컨테이너를 켜고 IDE 응답을 기다립니다 (최대 1분)...
          </Text>
        )}
        {error && <Banner status="error" title={error} />}
        {result && (
          <Banner
            status={trouble ? "warning" : "success"}
            title={`수업을 재개했습니다. 켜진 학생 ${result.started}명, IDE 응답 없음 ${result.unhealthy}명, 빈 작업 공간으로 새로 만든 학생 ${result.recreatedEmpty}명, 켜지 못한 학생 ${result.failed}명.`}
            description={
              result.recreatedEmpty > 0
                ? '빈 작업 공간 학생은 학생 목록에서 "도움 필요"로 표시됩니다.'
                : undefined
            }
          />
        )}
      </VStack>
    </Card>
  );
}

function DeleteCourse({
  busy,
  setBusy,
  deleted,
  setDeleted,
}: {
  busy: Busy;
  setBusy: (b: Busy) => void;
  deleted: DeleteResult | null;
  setDeleted: (r: DeleteResult) => void;
}) {
  const { courseId, dashboard } = useCourse();
  const router = useRouter();
  const showToast = useToast();
  const [typed, setTyped] = useState("");
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { name, status } = dashboard.course;

  // 종료됐거나 발급에 실패한 수업만 지울 수 있고, 이름을 똑같이 입력해야 한다.
  const deletable = status === "ENDED" || status === "FAILED";
  const enabled = deletable && busy === null && deleted === null && typed === name;

  async function remove() {
    setBusy("delete");
    setError(null);
    try {
      const r = await courses.deleteCourse(courseId, typed);
      setDeleted(r);
      setOpen(false);
      // 못 지운 자원이 있으면 강사가 이름을 읽을 수 있게 이 화면에 머문다.
      if (r.failures.length === 0) {
        showToast({ body: "수업을 삭제했습니다." });
        router.push("/instructor");
      }
    } catch (e) {
      setOpen(false);
      setError(e instanceof ApiError ? e.message : "삭제하지 못했습니다.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card padding={6}>
      <VStack gap={4}>
        <VStack gap={0.5}>
          <Heading level={2}>수업 삭제</Heading>
          <Text type="supporting">
            종료됐거나 발급에 실패한 수업만 지울 수 있습니다. 학생 컨테이너와 볼륨(코드), 수업 이미지, 서버 작업 폴더, 모든 기록이 지워지고 되돌릴 수 없습니다.
          </Text>
        </VStack>
        <TextInput
          label={`지우려면 수업 이름 ${name} 을 그대로 입력하세요.`}
          value={typed}
          onChange={setTyped}
          isDisabled={!deletable || busy !== null || deleted !== null}
          width={360}
        />
        <HStack>
          <Button
            variant="destructive"
            label="수업 삭제"
            isDisabled={!enabled}
            isLoading={busy === "delete"}
            onClick={() => setOpen(true)}
          />
        </HStack>
        {error && <Banner status="error" title={error} />}
        {deleted && (
          <VStack gap={3} hAlign="start">
            <Banner
              status={deleted.failures.length > 0 ? "warning" : "success"}
              title={`수업을 삭제했습니다. 컨테이너 ${deleted.removedContainers}개, 볼륨 ${deleted.removedVolumes}개를 지웠습니다.`}
              description={
                deleted.failures.length > 0
                  ? `지우지 못한 자원 ${deleted.failures.length}개는 직접 치워야 합니다: ${deleted.failures.join(", ")}`
                  : undefined
              }
            />
            <Button label="수업 목록으로" href="/instructor" />
          </VStack>
        )}
      </VStack>
      {open && (
        <AlertDialog
          isOpen
          onOpenChange={(next) => {
            if (busy !== "delete") setOpen(next);
          }}
          title="수업을 삭제할까요?"
          description="학생 컨테이너와 코드, 수업 이미지, 모든 기록이 지워집니다. 되돌릴 수 없습니다."
          actionLabel="수업 삭제"
          cancelLabel="취소"
          isActionLoading={busy === "delete"}
          onAction={remove}
        />
      )}
    </Card>
  );
}

export function SettingsTab() {
  // 종료, 재개, 삭제는 한 번에 하나만 돌린다. 삭제가 끝난 뒤에는 모두 잠근다.
  const [busy, setBusy] = useState<Busy>(null);
  const [deleted, setDeleted] = useState<DeleteResult | null>(null);
  const locked: Busy = busy ?? (deleted ? "delete" : null);

  return (
    <VStack gap={6} maxWidth={640}>
      <CourseInfo />
      <EndCourse busy={locked} setBusy={setBusy} />
      <ResumeCourse busy={locked} setBusy={setBusy} />
      <DeleteCourse busy={busy} setBusy={setBusy} deleted={deleted} setDeleted={setDeleted} />
    </VStack>
  );
}
