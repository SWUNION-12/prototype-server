"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState, useSyncExternalStore } from "react";
import { InstructorHeader } from "@/components/admin/InstructorHeader";
import { Notice } from "@/components/Notice";
import { CourseBadge } from "@/components/StatusBadge";
import { useToast } from "@/components/Toast";
import { ApiError, auth, courses } from "@/lib/api";
import { copyText } from "@/lib/clipboard";
import { studentCountLabel } from "@/lib/display";
import type { Course } from "@/lib/types";
import { usePoll } from "@/lib/use-poll";

const noSubscribe = () => () => {};
const useHost = () =>
  useSyncExternalStore(
    noSubscribe,
    () => window.location.host,
    () => "",
  );

const courseUrl = (id: number) => `/instructor/course?id=${id}`;

function CourseActions({ course }: { course: Course }) {
  if (course.status === "FAILED") {
    return (
      <div className="btns">
        <Link className="btn btn--line" href={courseUrl(course.id)}>
          로그 보기
        </Link>
        <Link className="btn btn--danger-line" href={`${courseUrl(course.id)}&tab=settings`}>
          수업 삭제
        </Link>
      </div>
    );
  }
  if (course.status === "LOCKING" || course.status === "BUILDING") {
    return (
      <div className="btns">
        <button type="button" className="btn" disabled>
          열기
        </button>
      </div>
    );
  }
  return (
    <div className="btns">
      <Link className="btn btn--blue" href={courseUrl(course.id)}>
        열기
      </Link>
    </div>
  );
}

function CourseCard({ course }: { course: Course }) {
  const isOff = course.status === "LOCKING" || course.status === "BUILDING" || course.status === "FAILED";
  return (
    <article className={isOff ? "card class-card is-off" : "card class-card"}>
      <div>
        <h2>
          {course.name}
          <CourseBadge status={course.status} />
        </h2>
        <p>
          Python {course.pythonVersion}
          {!isOff && ` · ${studentCountLabel(course.studentCount)} 접속`}
        </p>
        {course.status === "FAILED" && course.errorSummary && <p className="errline">{course.errorSummary}</p>}
      </div>
      <div>
        {course.entryCode ? (
          <>
            <div className="cap">수업 코드</div>
            <div className="code">{course.entryCode}</div>
          </>
        ) : (
          course.status !== "FAILED" && (
            <>
              <div className="cap">수업 코드</div>
              <div className="code-none">준비되면 생겨요</div>
            </>
          )
        )}
      </div>
      <CourseActions course={course} />
    </article>
  );
}

function HomeBody() {
  const params = useSearchParams();
  const showToast = useToast();
  const isEndedTab = params.get("tab") === "ended";
  const query = (params.get("q") ?? "").trim().toLowerCase();
  const { data: list, error } = usePoll(courses.list, 2000, (e) => e instanceof ApiError && e.status === 401);

  const inTab = (list ?? []).filter((c) => (c.status === "ENDED") === isEndedTab);
  const shown = inTab.filter((c) => !query || c.name.toLowerCase().includes(query));
  const openCount = (list ?? []).filter((c) => c.status !== "ENDED").length;
  const host = useHost();
  const [username, setUsername] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    auth
      .me()
      .then((me) => {
        if (live) setUsername(me.username);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  async function copyJoinUrl() {
    const url = `${window.location.origin}/join`;
    const ok = await copyText(url);
    showToast(
      ok
        ? { body: "학생 입장 주소를 복사했습니다." }
        : { body: `복사하지 못했습니다. 직접 전달해 주세요: ${url}`, type: "error" },
    );
  }

  return (
    <>
      <InstructorHeader
        actions={
          <Link className="btn btn--sm btn--blue" href="/instructor/new">
            새 수업 만들기
          </Link>
        }
      />
      <div className="band">
        <div className="wrap band-inner">
          <div>
            <h1>내 수업</h1>
            <div className="sub">{list ? `${username ? `${username} 선생님 · ` : ""}진행 중 ${openCount}개` : " "}</div>
          </div>
          <div className="joinurl">
            학생 입장 주소 <span className="mono">{host && `${host}/join`}</span>
            <button type="button" className="btn btn--pill" onClick={copyJoinUrl}>
              주소 복사
            </button>
          </div>
        </div>
      </div>
      <main className="wrap">
        <div className="listhead">최근에 만든 순서</div>
        {error != null && !list ? (
          <Notice
            status="error"
            title={error instanceof ApiError ? error.message : "수업 목록을 불러오지 못했습니다."}
          />
        ) : list === null ? (
          <div className="state">
            <span className="spinner" role="status" aria-label="불러오는 중" />
          </div>
        ) : shown.length === 0 ? (
          <div className="card state">
            {query ? (
              <b>검색 결과가 없습니다</b>
            ) : isEndedTab ? (
              <b>종료된 수업이 없습니다</b>
            ) : (
              <>
                <b>현재 만들어진 수업이 없습니다</b>
                <Link className="btn btn--blue" href="/instructor/new">
                  새 수업 만들기
                </Link>
              </>
            )}
          </div>
        ) : (
          <div className="class-list">
            {shown.map((course) => (
              <CourseCard key={course.id} course={course} />
            ))}
          </div>
        )}
      </main>
    </>
  );
}

export default function InstructorHomePage() {
  return (
    <div className="page page--nav page--bottom page--wave">
      <Suspense>
        <HomeBody />
      </Suspense>
    </div>
  );
}
