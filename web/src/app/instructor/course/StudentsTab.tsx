"use client";

import { AlertDialog } from "@astryxdesign/core/AlertDialog";
import { Card } from "@astryxdesign/core/Card";
import { HStack } from "@astryxdesign/core/HStack";
import { MoreMenu } from "@astryxdesign/core/MoreMenu";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { StatusDot } from "@astryxdesign/core/StatusDot";
import { Badge } from "@astryxdesign/core/Badge";
import { pixel, proportional, Table, type TableColumn, useTableRowExpansion } from "@astryxdesign/core/Table";
import { Text } from "@astryxdesign/core/Text";
import { TextInput } from "@astryxdesign/core/TextInput";
import { useToast } from "@astryxdesign/core/Toast";
import { Tooltip } from "@astryxdesign/core/Tooltip";
import { VStack } from "@astryxdesign/core/VStack";
import { useMemo, useState } from "react";
import { useCourse } from "@/components/admin/CourseContext";
import { EnvBadge } from "@/components/StatusBadge";
import { ApiError, courses } from "@/lib/api";
import {
  CLASSIFICATION_LABEL,
  formatStartup,
  formatTime,
  latestReport,
  LOCAL_LABEL,
  OUTCOME_LABEL,
  TRIGGER_LABEL,
} from "@/lib/display";
import type { ErrorCount, StudentRow } from "@/lib/types";

type Pending = { kind: "restart" | "reset-pin" | "remove"; student: StudentRow };

const CONFIRM: Record<Pending["kind"], { title: string; description: (n: string) => string; action: string }> = {
  restart: {
    title: "컨테이너를 재시작할까요?",
    description: (n) => `${n} 학생의 IDE가 잠시 끊기고, 실행 중인 프로그램이 멈춥니다. 저장한 코드는 남습니다.`,
    action: "재시작",
  },
  "reset-pin": {
    title: "PIN을 초기화할까요?",
    description: () => "PIN을 지웁니다. 학생이 다음 입장 때 새 PIN을 정합니다.",
    action: "초기화",
  },
  remove: {
    title: "학생을 제거할까요?",
    description: (n) => `${n} 학생의 컨테이너와 작성한 코드가 삭제됩니다. 되돌릴 수 없습니다.`,
    action: "제거",
  },
};

function RepeatedErrors({ errors, note }: { errors: ErrorCount[]; note: string }) {
  if (errors.length === 0) return null;
  return (
    <Card padding={4}>
      <VStack gap={2}>
        <HStack gap={2} vAlign="center" wrap="wrap">
          <Text weight="semibold">반복 오류</Text>
          <Text type="supporting">{note}</Text>
        </HStack>
        {errors.map((e) => (
          <HStack key={e.errorType} gap={3} vAlign="center" wrap="wrap">
            <Text type="code" weight="semibold">
              {e.errorType}
            </Text>
            <Text weight="medium">{e.count}건</Text>
            <Text type="supporting">{e.students.join(", ")}</Text>
          </HStack>
        ))}
      </VStack>
    </Card>
  );
}

function PinCell({ student }: { student: StudentRow }) {
  return <Text color={student.pinSet ? "primary" : "secondary"}>{student.pinSet ? "설정됨" : "미설정"}</Text>;
}

function EnvCell({ student }: { student: StudentRow }) {
  const details = [
    ...student.issues,
    ...student.extras.map((name) => `수업 스펙에 없는 패키지: ${name}`),
  ];
  const localLabel = LOCAL_LABEL[student.local.status];
  const badge = <EnvBadge status={student.envStatus} />;
  return (
    <VStack gap={1} hAlign="start">
      {details.length > 0 ? <Tooltip content={details.join("\n")}>{badge}</Tooltip> : badge}
      {localLabel && <Text type="supporting">{localLabel}</Text>}
    </VStack>
  );
}

function ConnectionCell({ student }: { student: StudentRow }) {
  const rows = [
    {
      on: student.containerRunning,
      label: student.containerRunning ? "컨테이너 실행 중" : "컨테이너 중지됨",
    },
    { on: student.ideConnected, label: student.ideConnected ? "IDE 접속 중" : "IDE 미접속" },
  ];
  return (
    <VStack gap={0.5}>
      {rows.map((r) => (
        <HStack key={r.label} gap={1.5} vAlign="center">
          <StatusDot variant={r.on ? "success" : "neutral"} label={r.label} />
          <Text type="supporting" color="primary">{r.label}</Text>
        </HStack>
      ))}
    </VStack>
  );
}

function TextList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <VStack gap={0.5}>
      <Text type="supporting" weight="semibold">
        {title}
      </Text>
      {items.map((item, i) => (
        <Text key={i}>{`· ${item}`}</Text>
      ))}
    </VStack>
  );
}

// 모델과 학생 PC에서 온 글이 많아 전부 텍스트 노드로만 그린다.
function StudentDetail({ student }: { student: StudentRow }) {
  const actions = student.recentActions.slice(0, 5);
  const seen = formatTime(student.lastSeenAt);
  const local = student.local;
  const pc = [local.os, local.arch, local.python].filter(Boolean).join(" / ");
  const localLabel = LOCAL_LABEL[local.status];
  return (
    <VStack gap={4} paddingBlock={3} paddingInline={4}>
      <HStack gap={4} wrap="wrap">
        <Text type="supporting">{seen ? `마지막 확인 ${seen}` : "마지막 확인 없음"}</Text>
        {student.startupMillis != null && <Text type="supporting">{formatStartup(student.startupMillis)}</Text>}
      </HStack>

      <VStack gap={1}>
        <Text weight="semibold">에이전트 조치 (최근 {actions.length}건)</Text>
        {actions.length === 0 && <Text color="secondary">조치 내역 없음</Text>}
        {actions.map((a, i) => {
          const head = [
            formatTime(a.at),
            TRIGGER_LABEL[a.trigger] ?? a.trigger,
            a.classification ? (CLASSIFICATION_LABEL[a.classification] ?? a.classification) : null,
            a.outcome ? (OUTCOME_LABEL[a.outcome] ?? a.outcome) : null,
          ]
            .filter(Boolean)
            .join(" · ");
          return (
            <VStack key={`${a.at}-${i}`} gap={0.5}>
              <Text type="supporting">{head}</Text>
              {a.tools.length > 0 && <Text type="code">{`도구: ${a.tools.join(" → ")}`}</Text>}
              {a.summary && <Text>{a.summary}</Text>}
            </VStack>
          );
        })}
      </VStack>

      <TextList title="환경 불일치" items={student.issues} />
      <TextList title="추가 패키지 경고" items={student.extras} />

      {(localLabel || pc || local.failed.length > 0) && (
        <VStack gap={1}>
          <Text weight="semibold">내 PC</Text>
          {localLabel && <Text>{localLabel}</Text>}
          {pc && <Text type="code">{pc}</Text>}
          <TextList title="실패한 항목" items={local.failed} />
        </VStack>
      )}
    </VStack>
  );
}

export function StudentsTab() {
  const { courseId, dashboard, refresh } = useCourse();
  const showToast = useToast();
  const [filter, setFilter] = useState("all");
  const [query, setQuery] = useState("");
  const [pending, setPending] = useState<Pending | null>(null);
  const [acting, setActing] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const helpCount = dashboard.students.filter((s) => s.needsHelp).length;
  const rows = useMemo(() => {
    const q = query.trim();
    return dashboard.students.filter(
      (s) => (filter === "all" || s.needsHelp) && (!q || s.name.includes(q)),
    );
  }, [dashboard.students, filter, query]);

  // 펼침 상태는 여기서 들고 있어 3초마다 새로 받아도 닫히지 않는다.
  const expansion = useTableRowExpansion<StudentRow>({
    expandedKeys: expanded,
    onToggle: (key) =>
      setExpanded((prev) => {
        const next = new Set(prev);
        if (!next.delete(key)) next.add(key);
        return next;
      }),
    getRowKey: (s) => String(s.sessionId),
    getRowLabel: (s) => s.name,
    renderExpanded: (s) => <StudentDetail student={s} />,
  });

  const isOpen = dashboard.course.status === "READY";

  const columns: TableColumn<StudentRow>[] = [
    {
      key: "name",
      header: "이름",
      width: pixel(180),
      renderCell: (student) => (
        <HStack gap={2} vAlign="center" wrap="wrap" data-needs-help={student.needsHelp || undefined}>
          <Text weight="semibold">{student.name}</Text>
          {student.needsHelp && <Badge variant="error" label="도움 필요" />}
        </HStack>
      ),
    },
    {
      key: "connection",
      header: "접속 상태",
      width: pixel(190),
      renderCell: (student) => <ConnectionCell student={student} />,
    },
    {
      key: "env",
      header: "환경 상태",
      width: pixel(130),
      renderCell: (student) => <EnvCell student={student} />,
    },
    {
      key: "pin",
      header: "PIN",
      width: pixel(100),
      renderCell: (student) => <PinCell student={student} />,
    },
    {
      key: "report",
      header: "AI 조치 보고",
      width: proportional(1),
      renderCell: (student) => {
        const report = latestReport(student);
        return (
          <HStack gap={3} vAlign="center">
            <Text color={report.text ? "primary" : "secondary"}>{report.text ?? "조치 내역 없음"}</Text>
            {report.at && (
              <Text type="supporting" textWrap="nowrap">
                {formatTime(report.at)}
              </Text>
            )}
          </HStack>
        );
      },
    },
    {
      key: "actions",
      header: "관리",
      width: pixel(72),
      renderCell: (student) => (
        <MoreMenu
          label={`${student.name} 관리 메뉴`}
          alignment="end"
          size="sm"
          items={[
            ...(student.needsHelp
              ? [
                  { label: "도움 처리 완료", onClick: () => void resolve(student) },
                  { type: "divider" as const },
                ]
              : []),
            {
              label: "재시작",
              isDisabled: !isOpen,
              onClick: () => setPending({ kind: "restart", student }),
            },
            { label: "PIN 초기화", onClick: () => setPending({ kind: "reset-pin", student }) },
            { type: "divider" as const },
            {
              label: "제거",
              variant: "destructive" as const,
              onClick: () => setPending({ kind: "remove", student }),
            },
          ]}
        />
      ),
    },
  ];

  async function resolve(student: StudentRow) {
    try {
      await courses.resolve(courseId, student.sessionId);
      showToast({ body: `${student.name} 학생을 처리 완료했습니다.` });
      await refresh();
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "처리하지 못했습니다.", type: "error" });
    }
  }

  async function confirm() {
    if (!pending) return;
    const { kind, student } = pending;
    setActing(true);
    try {
      if (kind === "restart") {
        const { ok } = await courses.restart(courseId, student.sessionId);
        showToast(
          ok
            ? { body: `${student.name} 학생의 컨테이너를 재시작했습니다.` }
            : { body: `${student.name} 학생의 컨테이너를 재시작하지 못했습니다.`, type: "error" },
        );
      } else if (kind === "reset-pin") {
        await courses.resetPin(courseId, student.sessionId);
        showToast({ body: `${student.name} 학생의 PIN을 지웠습니다. 다음 입장 때 새 PIN을 정합니다.` });
      } else {
        const { failures } = await courses.remove(courseId, student.sessionId);
        showToast(
          failures.length > 0
            ? {
                body: `${student.name} 학생을 제거했지만 지우지 못한 자원이 ${failures.length}개 있습니다: ${failures.join(", ")}`,
                type: "error",
              }
            : { body: `${student.name} 학생을 제거했습니다.` },
        );
      }
      setPending(null);
      await refresh();
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "처리하지 못했습니다.", type: "error" });
    } finally {
      setActing(false);
    }
  }

  return (
    <VStack gap={6}>
      <RepeatedErrors errors={dashboard.repeatedErrors} note={dashboard.repeatedErrorsNote} />

      <HStack hAlign="between" vAlign="center" wrap="wrap" gap={3}>
        <SegmentedControl label="필터" value={filter} onChange={setFilter}>
          <SegmentedControlItem value="all" label={`전체 ${dashboard.students.length}`} />
          <SegmentedControlItem value="help" label={`도움 필요 ${helpCount}`} />
        </SegmentedControl>
        <TextInput
          label="학생 검색"
          isLabelHidden
          type="search"
          startIcon="search"
          placeholder="이름 검색"
          value={query}
          onChange={setQuery}
          width={320}
        />
      </HStack>

      <div className="table-scroll">
        <Table
          aria-label="학생 목록"
          density="spacious"
          idKey="sessionId"
          data={rows}
          columns={columns}
          plugins={{ expansion }}
          emptyState={
            <VStack paddingBlock={6} paddingInline={4} hAlign="center">
              <Text color="secondary">
                {dashboard.students.length === 0
                  ? "아직 입장한 학생이 없습니다."
                  : "조건에 맞는 학생이 없습니다."}
              </Text>
            </VStack>
          }
        />
      </div>

      {pending && (
        <AlertDialog
          isOpen
          onOpenChange={(open) => {
            if (!open && !acting) setPending(null);
          }}
          title={CONFIRM[pending.kind].title}
          description={CONFIRM[pending.kind].description(pending.student.name)}
          actionLabel={CONFIRM[pending.kind].action}
          actionVariant={pending.kind === "remove" ? "destructive" : "primary"}
          cancelLabel="취소"
          isActionLoading={acting}
          onAction={confirm}
        />
      )}
    </VStack>
  );
}
