"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useCourse } from "@/components/admin/CourseContext";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { Notice } from "@/components/Notice";
import { CourseBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { ApiError, courses, INSTRUCTOR_HOME } from "@/lib/api";
import type { ResumeResult } from "@/lib/types";

type Busy = "end" | "resume" | "delete" | null;
type BusyProps = { busy: Busy; setBusy: (b: Busy) => void };

function CourseInfo() {
  const { course } = useCourse().dashboard;
  return (
    <section className="card card--tight setting">
      <h2>수업 정보</h2>
      <p>수업을 만든 뒤에는 환경을 바꿀 수 없습니다. 바꾸려면 새 수업을 만들어 주세요.</p>
      <div className="kv">
        <span>Python</span>
        <span className="mono">{course.pythonVersion}</span>
        <span>패키지</span>
        <span>
          {course.packages.length === 0
            ? "없음"
            : course.packages.map((p) => (
                <span key={p} className="tag">
                  {p}
                </span>
              ))}
        </span>
        <span>수업 코드</span>
        <span className="mono">{course.entryCode ?? "없음"}</span>
        <span>상태</span>
        <span>
          <CourseBadge status={course.status} />
        </span>
      </div>
      {course.errorSummary && <pre className="error-summary">{course.errorSummary}</pre>}
    </section>
  );
}

function EndCourse({ busy, setBusy }: BusyProps) {
  const { courseId, dashboard, refresh } = useCourse();
  const showToast = useToast();
  const [open, setOpen] = useState(false);
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
    <section className="card card--tight setting setting-row">
      <div>
        <h2>수업 종료</h2>
        <p>컨테이너만 멈추고 학생 볼륨(코드)은 남습니다.</p>
      </div>
      <button type="button" className="btn btn--red" disabled={!enabled} onClick={() => setOpen(true)}>
        {busy === "end" ? "종료하는 중" : "수업 종료"}
      </button>
      {open && (
        <ConfirmDialog
          title="수업을 종료할까요?"
          description="모든 학생의 IDE가 멈추고 새로 입장할 수 없습니다. 학생 코드는 남고, 종료한 수업은 다시 열 수 있습니다."
          actionLabel="수업 종료"
          isDanger
          isBusy={busy === "end"}
          onAction={end}
          onCancel={() => setOpen(false)}
        />
      )}
    </section>
  );
}

function ResumeCourse({ busy, setBusy }: BusyProps) {
  const { courseId, dashboard, refresh } = useCourse();
  const [result, setResult] = useState<ResumeResult | null>(null);
  const [error, setError] = useState<string | null>(null);
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
    <section className="card card--tight setting">
      <div className="setting-row">
        <div>
          <h2>수업 재개</h2>
          <p>종료된 수업만 다시 엽니다. 최대 1분쯤 걸리고, 학생 코드는 그대로 이어집니다.</p>
        </div>
        <button type="button" className="btn" disabled={!enabled} onClick={resume}>
          {busy === "resume" ? "재개하는 중" : "수업 재개"}
        </button>
      </div>
      {error && <Notice status="error" title={error} />}
      {result && (
        <Notice
          status={trouble ? "warning" : "success"}
          title={`수업을 재개했습니다. 켜진 학생 ${result.started}명, IDE 응답 없음 ${result.unhealthy}명, 빈 작업 공간으로 새로 만든 학생 ${result.recreatedEmpty}명, 켜지 못한 학생 ${result.failed}명.`}
        >
          {result.recreatedEmpty > 0 && '빈 작업 공간 학생은 학생 목록에서 "도움 필요"로 표시됩니다.'}
        </Notice>
      )}
    </section>
  );
}

function DeleteCourse({ busy, setBusy }: BusyProps) {
  const { courseId, dashboard, deleted, setDeleted } = useCourse();
  const router = useRouter();
  const showToast = useToast();
  const [typed, setTyped] = useState("");
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { name, status } = dashboard.course;

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
        router.push(INSTRUCTOR_HOME);
      }
    } catch (e) {
      setOpen(false);
      setError(e instanceof ApiError ? e.message : "삭제하지 못했습니다.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="card card--tight setting">
      <h2>수업 삭제</h2>
      <p>종료됐거나 발급에 실패한 수업만 지울 수 있습니다. 학생 코드와 모든 기록이 지워지고 되돌릴 수 없습니다.</p>
      <div className="delete-row">
        <input
          placeholder={`수업 이름 ${name} 을 그대로 입력하세요`}
          aria-label="삭제할 수업 이름"
          value={typed}
          disabled={!deletable || busy !== null || deleted !== null}
          onChange={(e) => setTyped(e.target.value)}
        />
        <button type="button" className="btn btn--red" disabled={!enabled} onClick={() => setOpen(true)}>
          {busy === "delete" ? "삭제하는 중" : "수업 삭제"}
        </button>
      </div>
      {error && <Notice status="error" title={error} />}
      {deleted && (
        <>
          <Notice
            status={deleted.failures.length > 0 ? "warning" : "success"}
            title={`수업을 삭제했습니다. 컨테이너 ${deleted.removedContainers}개, 볼륨 ${deleted.removedVolumes}개를 지웠습니다.`}
          >
            {deleted.failures.length > 0 &&
              `지우지 못한 자원 ${deleted.failures.length}개는 직접 치워야 합니다: ${deleted.failures.join(", ")}`}
          </Notice>
          <Link className="btn" href={INSTRUCTOR_HOME} style={{ marginTop: 12 }}>
            수업 목록으로
          </Link>
        </>
      )}
      {open && (
        <ConfirmDialog
          title="수업을 삭제할까요?"
          description="학생 컨테이너와 코드, 수업 이미지, 모든 기록이 지워집니다. 되돌릴 수 없습니다."
          actionLabel="수업 삭제"
          isDanger
          isBusy={busy === "delete"}
          onAction={remove}
          onCancel={() => setOpen(false)}
        />
      )}
    </section>
  );
}

export function SettingsTab() {
  // 종료, 재개, 삭제는 한 번에 하나만 돌린다. 삭제가 끝난 뒤에는 모두 잠근다.
  const [busy, setBusy] = useState<Busy>(null);
  const { deleted } = useCourse();
  const locked: Busy = busy ?? (deleted ? "delete" : null);

  return (
    <div className="settings">
      <CourseInfo />
      <EndCourse busy={locked} setBusy={setBusy} />
      <ResumeCourse busy={locked} setBusy={setBusy} />
      <DeleteCourse busy={busy} setBusy={setBusy} />
    </div>
  );
}
