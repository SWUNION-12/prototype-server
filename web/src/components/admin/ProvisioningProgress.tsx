"use client";

import { useEffect, useState } from "react";
import { courses } from "@/lib/api";
import { formatElapsed } from "@/lib/display";
import { packageName } from "@/lib/packages";

const STEPS = ["의존성 확인", "이미지 빌드", "완료"];

type Props = {
  courseId: number;
  status: "LOCKING" | "BUILDING";
  packages: string[];
};

/** 대시보드 응답에는 생성 시각이 없어 수업 정보를 한 번 읽는다. 못 읽으면 경과 시간만 빠진다. */
function useElapsedSeconds(courseId: number): number | null {
  const [createdAt, setCreatedAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let live = true;
    courses
      .get(courseId)
      .then((course) => {
        const time = course.createdAt ? new Date(course.createdAt).getTime() : Number.NaN;
        if (live && !Number.isNaN(time)) setCreatedAt(time);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [courseId]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  return createdAt === null ? null : Math.max(0, Math.floor((now - createdAt) / 1000));
}

/** 서버가 단계만 알려 주므로 퍼센트나 남은 시간은 보여 주지 않는다. 현재 단계 막대는 "작업 중"만 나타낸다. */
export function ProvisioningProgress({ courseId, status, packages }: Props) {
  const elapsed = useElapsedSeconds(courseId);
  const active = status === "LOCKING" ? 0 : 1;
  const hasTorch = packages.some((line) => packageName(line) === "torch");

  return (
    <div className="card card--tight provision">
      <div className="provision-top">
        <b role="status">환경을 준비하고 있어요</b>
        {elapsed !== null && <span>경과 {formatElapsed(elapsed)}</span>}
      </div>
      <ol aria-label="환경 준비 단계">
        {STEPS.map((step, index) => {
          const state = index < active ? "is-done" : index === active ? "is-active" : "";
          return (
            <li key={step} className={state} aria-current={index === active ? "step" : undefined}>
              <i />
              {index < active && "✓ "}
              {index === active ? `${step} 중` : step}
            </li>
          );
        })}
      </ol>
      <p className="small">
        {hasTorch ? "torch는 이미지 빌드에 6분 넘게 걸린 적이 있어요." : "패키지가 크면 몇 분 더 걸릴 수 있어요."} 끝나면 수업
        코드가 나타납니다.
      </p>
    </div>
  );
}
