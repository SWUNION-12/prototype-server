"use client";

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { useCourse } from "@/components/admin/CourseContext";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ChevronRightIcon, SearchIcon } from "@/components/icons";
import { EnvBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
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

const MENU_HEIGHT = 190;

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
    <div className="card card--tight repeat">
      <b>반복 오류</b> <span>· {note}</span>
      {errors.map((e) => (
        <div key={e.errorType}>
          <code>{e.errorType}</code>
          {e.count}건 <span>· {e.students.join(", ")}</span>
        </div>
      ))}
    </div>
  );
}

function StudentMenu({
  student,
  isOpenCourse,
  onResolve,
  onPick,
}: {
  student: StudentRow;
  isOpenCourse: boolean;
  onResolve: () => void;
  onPick: (kind: Pending["kind"]) => void;
}) {
  const [open, setOpen] = useState(false);
  const [opensUp, setOpensUp] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  function toggle() {
    // 화면 아래쪽 행은 메뉴가 잘리지 않게 위로 연다.
    const bottom = root.current?.getBoundingClientRect().bottom ?? 0;
    setOpensUp(window.innerHeight - bottom < MENU_HEIGHT);
    setOpen(!open);
  }

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const pick = (action: () => void) => () => {
    setOpen(false);
    action();
  };

  return (
    <div className="menu" ref={root}>
      <button
        type="button"
        aria-label={`${student.name} 관리 메뉴`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={toggle}
      >
        ⋯
      </button>
      {open && (
        <div className={opensUp ? "menu-list menu-list--up" : "menu-list"} role="menu">
          {student.needsHelp && (
            <>
              <button type="button" role="menuitem" onClick={pick(onResolve)}>
                도움 처리 완료
              </button>
              <hr />
            </>
          )}
          <button type="button" role="menuitem" disabled={!isOpenCourse} onClick={pick(() => onPick("restart"))}>
            재시작
          </button>
          <button type="button" role="menuitem" onClick={pick(() => onPick("reset-pin"))}>
            PIN 초기화
          </button>
          <hr />
          <button type="button" role="menuitem" className="is-danger" onClick={pick(() => onPick("remove"))}>
            제거
          </button>
        </div>
      )}
    </div>
  );
}

function TextList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <section>
      <h4>{title}</h4>
      {items.map((item, i) => (
        <div key={i}>· {item}</div>
      ))}
    </section>
  );
}

// 모델과 학생 PC에서 온 글이 많아 전부 텍스트 노드로만 그린다.
function StudentDetail({ student }: { student: StudentRow }) {
  const actions = student.recentActions.slice(0, 5);
  const seen = formatTime(student.lastSeenAt);
  const { local } = student;
  const pc = [local.os, local.arch, local.python].filter(Boolean).join(" / ");
  const localLabel = LOCAL_LABEL[local.status];
  return (
    <div className="detail-grid">
      <div className="detail-meta">
        {seen ? `마지막 확인 ${seen}` : "마지막 확인 없음"}
        {student.startupMillis != null && ` · ${formatStartup(student.startupMillis)}`}
      </div>
      <div>
        <h4>에이전트 조치 (최근 {actions.length}건)</h4>
        {actions.length === 0 && <span className="muted">조치 내역 없음</span>}
        {actions.map((a, i) => (
          <div key={`${a.at}-${i}`} className="action">
            <small>
              {[
                formatTime(a.at),
                TRIGGER_LABEL[a.trigger] ?? a.trigger,
                a.classification ? (CLASSIFICATION_LABEL[a.classification] ?? a.classification) : null,
                a.outcome ? (OUTCOME_LABEL[a.outcome] ?? a.outcome) : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </small>
            {a.tools.length > 0 && <code>도구: {a.tools.join(" → ")}</code>}
            {a.summary && <div>{a.summary}</div>}
          </div>
        ))}
      </div>
      <div>
        <TextList title="환경 불일치" items={student.issues} />
        <TextList title="추가 패키지 경고" items={student.extras} />
        {(localLabel || pc || local.failed.length > 0) && (
          <section>
            <h4>내 PC</h4>
            {localLabel}
            {localLabel && pc && " · "}
            {pc && <code>{pc}</code>}
            {local.failed.length > 0 && <div>실패한 항목: {local.failed.join(", ")}</div>}
          </section>
        )}
      </div>
    </div>
  );
}

function StudentRows({
  student,
  isOpenCourse,
  onResolve,
  onPick,
}: {
  student: StudentRow;
  isOpenCourse: boolean;
  onResolve: () => void;
  onPick: (kind: Pending["kind"]) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const report = latestReport(student);
  const envDetails = [...student.issues, ...student.extras.map((name) => `수업 스펙에 없는 패키지: ${name}`)];
  const localLabel = LOCAL_LABEL[student.local.status];

  return (
    <Fragment>
      <tr className={student.needsHelp ? "needs-help" : undefined}>
        <td>
          <button
            type="button"
            className="chev"
            aria-label={`${student.name} 상세 ${expanded ? "접기" : "펼치기"}`}
            aria-expanded={expanded}
            onClick={() => setExpanded(!expanded)}
          >
            <ChevronRightIcon />
          </button>
        </td>
        <td className="sname">
          {student.name}
          {student.needsHelp && <span className="help-tag">도움 필요</span>}
        </td>
        <td>
          <div className={student.containerRunning ? "conn is-on" : "conn"}>
            {student.containerRunning ? "컨테이너 실행 중" : "컨테이너 중지됨"}
          </div>
          <div className={student.ideConnected ? "conn is-on" : "conn"}>
            {student.ideConnected ? "IDE 접속 중" : "IDE 미접속"}
          </div>
        </td>
        <td>
          <EnvBadge status={student.envStatus} title={envDetails.join("\n") || undefined} />
          {localLabel && <div className="row-sub">{localLabel}</div>}
        </td>
        <td className={student.pinSet ? undefined : "muted"}>{student.pinSet ? "설정됨" : "미설정"}</td>
        <td>
          {report.text ?? <span className="muted">조치 내역 없음</span>}
          {report.at && <span className="row-time">{formatTime(report.at)}</span>}
        </td>
        <td className="right">
          <StudentMenu student={student} isOpenCourse={isOpenCourse} onResolve={onResolve} onPick={onPick} />
        </td>
      </tr>
      {expanded && (
        <tr className="detail">
          <td />
          <td colSpan={6}>
            <StudentDetail student={student} />
          </td>
        </tr>
      )}
    </Fragment>
  );
}

export function StudentsTab() {
  const { courseId, dashboard, refresh } = useCourse();
  const showToast = useToast();
  const [filter, setFilter] = useState<"all" | "help">("all");
  const [query, setQuery] = useState("");
  const [pending, setPending] = useState<Pending | null>(null);
  const [acting, setActing] = useState(false);

  const helpCount = dashboard.students.filter((s) => s.needsHelp).length;
  const rows = useMemo(() => {
    const q = query.trim();
    return dashboard.students
      .filter((s) => (filter === "all" || s.needsHelp) && (!q || s.name.includes(q)))
      .sort((a, b) => Number(b.needsHelp) - Number(a.needsHelp));
  }, [dashboard.students, filter, query]);

  const isOpenCourse = dashboard.course.status === "READY";

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

  if (dashboard.students.length === 0) {
    return (
      <div className="card state" style={{ marginTop: 18 }}>
        <b>아직 입장한 학생이 없습니다</b>
      </div>
    );
  }

  return (
    <>
      <RepeatedErrors errors={dashboard.repeatedErrors} note={dashboard.repeatedErrorsNote} />

      <div className="tools">
        <div className="seg" role="group" aria-label="필터">
          <button type="button" aria-pressed={filter === "all"} onClick={() => setFilter("all")}>
            전체 <span>{dashboard.students.length}</span>
          </button>
          <button type="button" aria-pressed={filter === "help"} onClick={() => setFilter("help")}>
            도움 필요 <span>{helpCount}</span>
          </button>
        </div>
        <label className="search">
          <SearchIcon />
          <input
            type="search"
            placeholder="이름 검색"
            aria-label="이름 검색"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
      </div>

      {rows.length === 0 ? (
        <div className="card state">
          <b>조건에 맞는 학생이 없습니다</b>
        </div>
      ) : (
        <div className="card card--tight">
          <table className="students">
            <thead>
              <tr>
                <th aria-label="상세" />
                <th>이름</th>
                <th>접속 상태</th>
                <th>환경 상태</th>
                <th>PIN</th>
                <th>AI 조치 보고</th>
                <th className="right">관리</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((student) => (
                <StudentRows
                  key={student.sessionId}
                  student={student}
                  isOpenCourse={isOpenCourse}
                  onResolve={() => void resolve(student)}
                  onPick={(kind) => setPending({ kind, student })}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pending && (
        <ConfirmDialog
          title={CONFIRM[pending.kind].title}
          description={CONFIRM[pending.kind].description(pending.student.name)}
          actionLabel={CONFIRM[pending.kind].action}
          isDanger={pending.kind === "remove"}
          isBusy={acting}
          onAction={confirm}
          onCancel={() => setPending(null)}
        />
      )}
    </>
  );
}
