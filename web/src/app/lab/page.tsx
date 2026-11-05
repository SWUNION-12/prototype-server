"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { CloseIcon, LaptopIcon } from "@/components/icons";
import { IdeFrame } from "@/components/lab/IdeFrame";
import { LocalPanel } from "@/components/lab/LocalPanel";
import { EnvBadge } from "@/components/StatusBadge";
import { ApiError, CODE_ZIP_URL, student } from "@/lib/api";
import { RESUMED_KEY, SERVICE_NAME } from "@/lib/display";
import { usePoll } from "@/lib/use-poll";

// 쿠키가 없거나 무효(다른 곳에서 재입장해 토큰이 바뀜)이면 404가 와서 조회를 멈추고 이 화면을 보인다.
function Lost() {
  return (
    <div className="state state--full">
      <b>입장 정보를 찾을 수 없습니다</b>
      수업 코드, 이름, PIN으로 다시 입장해 주세요.
      <Link className="btn btn--blue" href="/join">
        입장 페이지로
      </Link>
    </div>
  );
}

function Banner({ status = "warning", title, text, onDismiss }: {
  status?: "warning" | "error" | "success";
  title: string;
  text?: string;
  onDismiss?: () => void;
}) {
  return (
    <div className={`lab-banner lab-banner--${status}`} role={status === "error" ? "alert" : "status"}>
      <div>
        <b>{title}</b>
        {text && <span>{text}</span>}
      </div>
      {onDismiss && (
        <button type="button" aria-label="닫기" onClick={onDismiss}>
          ×
        </button>
      )}
    </div>
  );
}

function Lab({ resumed }: { resumed: boolean }) {
  const { data: me, error } = usePoll(student.me, 5000, (e) => e instanceof ApiError && e.status === 404);
  const [isLocalOpen, setIsLocalOpen] = useState(false);
  const [resumedDismissed, setResumedDismissed] = useState(false);

  if (error instanceof ApiError && error.status === 404) return <Lost />;

  if (!me) {
    return (
      <div className="state state--full">
        {error != null ? (
          "실습 정보를 불러오지 못했습니다. 다시 시도하고 있어요."
        ) : (
          <span className="spinner" role="status" aria-label="불러오는 중" />
        )}
      </div>
    );
  }

  const isEnded = me.courseStatus === "ENDED";
  const isIdeUrlSafe = /^https?:\/\/\S+\/$/.test(me.ideUrl);

  return (
    <div className="lab">
      <header className="lab-header">
        <span className="logo">{SERVICE_NAME}</span>
        <span className="lab-sep" aria-hidden />
        <span className="lab-course">{me.courseName}</span>
        <span className="lab-name">{me.name}</span>
        <div className="lab-actions">
          <EnvBadge status={me.envStatus} prefix="환경" title={me.issuesText.join("\n") || undefined} />
          <button
            type="button"
            className={isLocalOpen ? "btn btn--sm" : "btn btn--sm btn--ghost"}
            aria-pressed={isLocalOpen}
            onClick={() => setIsLocalOpen(!isLocalOpen)}
          >
            <LaptopIcon />내 PC로 이어가기
          </button>
        </div>
      </header>

      {error != null && <Banner title="서버에 연결하지 못했습니다. 다시 시도하고 있어요." />}
      {me.envStatus === "MISMATCH" && me.issuesText.length > 0 && (
        <Banner title="내 환경이 수업과 다릅니다" text={me.issuesText.join(" · ")} />
      )}
      {resumed && !resumedDismissed && (
        <Banner
          status="success"
          title="이전 세션으로 돌아왔습니다."
          text="작업하던 파일은 그대로 있습니다."
          onDismiss={() => setResumedDismissed(true)}
        />
      )}
      {me.workspaceLost && (
        <Banner title="이전 작업 공간을 찾지 못해 새로 만들었습니다." text="이전에 작성한 코드는 복구되지 않았어요." />
      )}

      <div className="lab-body">
        <section className="lab-ide" aria-label="실습 IDE">
          {isEnded ? (
            <div className="state">
              <b>수업이 종료되었습니다</b>
              작성한 코드는 남아 있어요.
              {/* 파일 주소는 Next 링크가 미리 받지 않게 일반 앵커로 둔다. */}
              <a className="btn btn--blue" href={CODE_ZIP_URL}>
                코드 zip 다운로드
              </a>
            </div>
          ) : isIdeUrlSafe ? (
            <IdeFrame ideUrl={me.ideUrl} password={me.idePassword} />
          ) : (
            <div className="state">IDE 주소를 확인할 수 없습니다. 선생님에게 알려 주세요.</div>
          )}
        </section>

        {isLocalOpen && (
          <aside className="panel" aria-label="내 PC로 이어가기">
            <div className="panel-head">
              <h2>내 PC로 이어가기</h2>
              <button type="button" aria-label="패널 닫기" onClick={() => setIsLocalOpen(false)}>
                <CloseIcon />
              </button>
            </div>
            <div className="panel-body">
              <LocalPanel />
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}

export default function LabPage() {
  // 재입장 안내는 한 번만 보인다. 입장 화면이 탭 저장소에 남긴 표시를 읽고(렌더 중에는 읽기만 한다) 효과에서 지운다.
  const [resumed] = useState(() => {
    try {
      return typeof window !== "undefined" && sessionStorage.getItem(RESUMED_KEY) === "1";
    } catch {
      return false;
    }
  });
  useEffect(() => {
    try {
      sessionStorage.removeItem(RESUMED_KEY);
    } catch {
      // 저장소가 막혀 있어도 화면에는 영향이 없다.
    }
  }, []);
  // 주소에 옛 토큰이 남아 있어도 값은 쓰지 않고 주소창에서만 지운다.
  useEffect(() => {
    const url = new URL(window.location.href);
    if (!url.searchParams.has("t") && !url.searchParams.has("resumed")) return;
    url.searchParams.delete("t");
    url.searchParams.delete("resumed");
    window.history.replaceState(null, "", url);
  }, []);
  return <Lab resumed={resumed} />;
}
