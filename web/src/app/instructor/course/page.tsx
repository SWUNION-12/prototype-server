"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useState } from "react";
import { CourseProvider } from "@/components/admin/CourseContext";
import { InstructorHeader } from "@/components/admin/InstructorHeader";
import { ProvisioningProgress } from "@/components/admin/ProvisioningProgress";
import { Notice } from "@/components/Notice";
import { useToast } from "@/components/Toast";
import { ApiError, courses, INSTRUCTOR_HOME } from "@/lib/api";
import { copyText } from "@/lib/clipboard";
import { studentCountLabel } from "@/lib/display";
import type { Dashboard, DeleteResult } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";
import { SettingsTab } from "./SettingsTab";
import { StudentsTab } from "./StudentsTab";

function EntryInfo({ entryCode }: { entryCode: string }) {
  const showToast = useToast();

  async function copy(text: string, done: string) {
    const ok = await copyText(text);
    showToast(ok ? { body: done } : { body: `복사하지 못했습니다. 직접 전달해 주세요: ${text}`, type: "error" });
  }

  return (
    <div className="codebox">
      <span className="cap">수업 코드</span>
      <b>{entryCode}</b>
      <button type="button" className="btn btn--pill" onClick={() => copy(entryCode, "수업 코드를 복사했습니다.")}>
        코드 복사
      </button>
      <button
        type="button"
        className="btn btn--pill"
        onClick={() => copy(`${window.location.origin}/join?code=${entryCode}`, "입장 링크를 복사했습니다.")}
      >
        입장 링크 복사
      </button>
    </div>
  );
}

function StatusBanner({ dashboard, deleted }: { dashboard: Dashboard; deleted: boolean }) {
  const { id, status, errorSummary, packages } = dashboard.course;
  // 삭제 뒤에도 못 지운 자원 때문에 화면에 머물 때, 대시보드 값은 삭제 전이라 그대로 두면 거짓이 된다.
  if (deleted) {
    return (
      <div style={{ marginTop: 18 }}>
        <Notice status="warning" title="삭제된 수업입니다">
          더 이상 다시 열 수 없습니다. 지우지 못한 자원은 설정 탭에 남아 있습니다.
        </Notice>
      </div>
    );
  }
  if (status === "LOCKING" || status === "BUILDING") {
    return <ProvisioningProgress courseId={id} status={status} packages={packages} />;
  }
  if (status === "FAILED") {
    return (
      <div style={{ marginTop: 18 }}>
        <Notice status="error" title="환경을 만들지 못했습니다">
          패키지나 버전을 바꿔 새 수업을 만들어 주세요. 이 수업은 설정 탭에서 삭제할 수 있습니다.
          {errorSummary && <pre className="error-summary">{errorSummary}</pre>}
        </Notice>
      </div>
    );
  }
  if (status === "ENDED") {
    return (
      <div style={{ marginTop: 18 }}>
        <Notice title="종료된 수업입니다">학생 코드는 남아 있고, 설정 탭에서 다시 열 수 있습니다.</Notice>
      </div>
    );
  }
  return null;
}

function NotFound() {
  return (
    <div className="card state" style={{ marginTop: 18 }}>
      <b>수업을 찾을 수 없습니다</b>
      삭제됐거나 주소가 올바르지 않습니다.
      <Link className="btn btn--blue" href={INSTRUCTOR_HOME}>
        수업 목록으로
      </Link>
    </div>
  );
}

// 정적 빌드에서는 경로 값을 쓸 수 없어 수업 번호와 탭을 쿼리(?id=1&tab=settings)로 받는다.
function CourseView({ courseId, tab }: { courseId: number; tab: "students" | "settings" }) {
  const fetcher = useCallback(() => courses.dashboard(courseId), [courseId]);
  const { data, error, refresh } = usePoll(
    fetcher,
    3000,
    (e) => e instanceof ApiError && (e.status === 404 || e.status === 401),
  );
  const [deleted, setDeleted] = useState<DeleteResult | null>(null);
  const base = `/instructor/course?id=${courseId}`;

  return (
    <>
      <div className="band">
        <div className="wrap">
          <Link className="backlink" href={INSTRUCTOR_HOME}>
            ‹ 이전
          </Link>
          <div className="band-inner">
            <div>
              <h1>{data?.course.name ?? " "}</h1>
              <div className="sub">
                {data
                  ? `Python ${data.course.pythonVersion} · ${studentCountLabel(data.students.length)}`
                  : " "}
              </div>
            </div>
            {data?.course.entryCode && data.course.status === "READY" && (
              <EntryInfo entryCode={data.course.entryCode} />
            )}
          </div>
        </div>
      </div>
      <main className="wrap" style={{ paddingBottom: 24 }}>
        {data ? (
          <>
            {error instanceof ApiError && error.status === 401 && (
              <div style={{ marginTop: 18 }}>
                <Notice status="error" title={error.message} />
              </div>
            )}
            <StatusBanner dashboard={data} deleted={deleted !== null} />
            <nav className="tabs">
              <Link href={base} aria-current={tab === "students" ? "page" : undefined}>
                학생 목록
              </Link>
              <Link href={`${base}&tab=settings`} aria-current={tab === "settings" ? "page" : undefined}>
                수업 설정
              </Link>
            </nav>
            <CourseProvider value={{ courseId, dashboard: data, refresh, deleted, setDeleted }}>
              {tab === "settings" ? <SettingsTab /> : <StudentsTab />}
            </CourseProvider>
          </>
        ) : error != null ? (
          error instanceof ApiError && error.status === 404 ? (
            <NotFound />
          ) : (
            <div style={{ marginTop: 18 }}>
              <Notice
                status="error"
                title={error instanceof ApiError ? error.message : "수업을 불러오지 못했습니다."}
              />
            </div>
          )
        ) : (
          <div className="state">
            <span className="spinner" role="status" aria-label="불러오는 중" />
          </div>
        )}
      </main>
    </>
  );
}

function CourseEntry() {
  const params = useSearchParams();
  const raw = params.get("id") ?? "";
  const courseId = /^\d+$/.test(raw) ? Number(raw) : null;
  return (
    <>
      <InstructorHeader />
      {courseId === null ? (
        <main className="wrap">
          <NotFound />
        </main>
      ) : (
        // key: 다른 수업으로 바뀌면 삭제 결과 같은 화면 상태를 이어받지 않도록 새로 만든다.
        <CourseView key={courseId} courseId={courseId} tab={params.get("tab") === "settings" ? "settings" : "students"} />
      )}
    </>
  );
}

export default function CoursePage() {
  return (
    <div className="page page--nav">
      <Suspense>
        <CourseEntry />
      </Suspense>
    </div>
  );
}
