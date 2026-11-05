"use client";

import { AlertDialog } from "@astryxdesign/core/AlertDialog";
import { Banner } from "@astryxdesign/core/Banner";
import { Button } from "@astryxdesign/core/Button";
import { Card } from "@astryxdesign/core/Card";
import { Heading } from "@astryxdesign/core/Heading";
import { HStack } from "@astryxdesign/core/HStack";
import { MetadataList, MetadataListItem } from "@astryxdesign/core/MetadataList";
import { NumberInput } from "@astryxdesign/core/NumberInput";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { useToast } from "@astryxdesign/core/Toast";
import { Token } from "@astryxdesign/core/Token";
import { VStack } from "@astryxdesign/core/VStack";
import { useState } from "react";
import { useCourse } from "@/components/admin/CourseContext";
import { ApiError, courses } from "@/lib/api";

function BasicInfo() {
  const { courseId, dashboard, refresh } = useCourse();
  const { course } = dashboard;
  const showToast = useToast();
  // 3초마다 들어오는 조회 값이 입력 중인 내용을 덮어쓰지 않도록 고친 칸만 따로 둔다.
  const [draftName, setDraftName] = useState<string | null>(null);
  const [draftCapacity, setDraftCapacity] = useState<number | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const name = draftName ?? course.name;
  const capacity = draftCapacity === undefined ? course.capacity : draftCapacity;
  const trimmed = name.trim();
  const changed = trimmed !== course.name || capacity !== course.capacity;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await courses.update(courseId, { name: trimmed, capacity });
      await refresh();
      setDraftName(null);
      setDraftCapacity(undefined);
      showToast({ body: "저장했습니다." });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "저장하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card padding={6}>
      <VStack gap={5}>
        <Heading level={2}>기본 정보</Heading>
        {error && <Banner status="error" title={error} />}
        <TextInput label="수업명" value={name} onChange={setDraftName} />
        <VStack gap={2}>
          <NumberInput
            label="학생 수"
            value={capacity}
            onChange={setDraftCapacity}
            hasClear
            min={1}
            isIntegerOnly
            placeholder="제한 없음"
            width={160}
          />
          <Text type="supporting">
            이 인원까지 입장할 수 있습니다. 비워 두면 제한이 없습니다. 컨테이너는 학생이 입장할 때 만들어집니다.
          </Text>
        </VStack>
        <HStack hAlign="end">
          <Button
            variant="primary"
            label="저장"
            onClick={save}
            isLoading={busy}
            isDisabled={!changed || !trimmed}
          />
        </HStack>
      </VStack>
    </Card>
  );
}

function EnvironmentInfo() {
  const { course } = useCourse().dashboard;
  return (
    <Card padding={6}>
      <VStack gap={4}>
        <VStack gap={0.5}>
          <Heading level={2}>환경 정보</Heading>
          <Text type="supporting">
            수업 중에는 환경을 변경할 수 없습니다. 변경이 필요하면 새 프로젝트를 생성하세요.
          </Text>
        </VStack>
        <MetadataList label={{ position: "start", width: 120 }}>
          <MetadataListItem label="Python">
            <Text type="code">{course.pythonVersion}</Text>
          </MetadataListItem>
          <MetadataListItem label="의존성">
            <HStack gap={1.5} wrap="wrap">
              {course.packages.map((p) => (
                <Token key={p} label={p} size="sm" />
              ))}
            </HStack>
          </MetadataListItem>
        </MetadataList>
      </VStack>
    </Card>
  );
}

function EndCourse() {
  const { courseId, dashboard, refresh } = useCourse();
  const showToast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  if (dashboard.course.status !== "READY") return null;

  async function end() {
    setBusy(true);
    try {
      const r = await courses.end(courseId);
      showToast(
        r.failed > 0
          ? { body: `컨테이너 ${r.failed}개를 멈추지 못했습니다. 다시 시도해 주세요.`, type: "error" }
          : { body: `수업을 종료했습니다. 컨테이너 ${r.stopped}개를 멈췄습니다.` },
      );
      setOpen(false);
      await refresh();
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "종료하지 못했습니다.", type: "error" });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card padding={6}>
      <HStack hAlign="between" vAlign="center" gap={4} wrap="wrap">
        <VStack gap={0.5}>
          <Heading level={2}>수업 종료</Heading>
          <Text type="supporting">모든 학생의 컨테이너를 멈춥니다. 학생 코드는 남습니다.</Text>
        </VStack>
        <Button variant="destructive" label="수업 종료" onClick={() => setOpen(true)} />
      </HStack>
      {open && (
        <AlertDialog
          isOpen
          onOpenChange={(next) => {
            if (!busy) setOpen(next);
          }}
          title="수업을 종료할까요?"
          description="모든 학생의 IDE가 멈추고 새로 입장할 수 없습니다. 종료한 수업은 다시 열 수 없습니다."
          actionLabel="수업 종료"
          cancelLabel="취소"
          isActionLoading={busy}
          onAction={end}
        />
      )}
    </Card>
  );
}

export default function SettingsPage() {
  return (
    <VStack gap={6} maxWidth={640}>
      <BasicInfo />
      <EnvironmentInfo />
      <EndCourse />
    </VStack>
  );
}
