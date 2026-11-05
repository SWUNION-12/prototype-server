"use client";

import { Card } from "@astryxdesign/core/Card";
import { HStack } from "@astryxdesign/core/HStack";
import { Icon } from "@astryxdesign/core/Icon";
import { ProgressBar } from "@astryxdesign/core/ProgressBar";
import { Text } from "@astryxdesign/core/Text";
import { VStack } from "@astryxdesign/core/VStack";
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

/**
 * 환경 발급 진행 표시. 서버가 단계만 알려 주므로 퍼센트나 남은 시간은 보여 주지 않는다.
 * 현재 단계의 막대는 움직이는 애니메이션으로 "작업 중"만 나타낸다.
 */
export function ProvisioningProgress({ courseId, status, packages }: Props) {
  const elapsed = useElapsedSeconds(courseId);
  const active = status === "LOCKING" ? 0 : 1;
  const hasTorch = packages.some((line) => packageName(line) === "torch");

  return (
    <Card padding={4}>
      <VStack gap={3}>
        <HStack hAlign="between" vAlign="center" gap={3} wrap="wrap">
          <Text weight="semibold" role="status">
            환경을 준비하고 있어요
          </Text>
          {elapsed !== null && (
            <Text type="supporting" hasTabularNumbers>
              경과 {formatElapsed(elapsed)}
            </Text>
          )}
        </HStack>

        <ol className="provision-steps" aria-label="환경 준비 단계">
          {STEPS.map((step, index) => {
            const isDone = index < active;
            const isActive = index === active;
            return (
              <li key={step} aria-current={isActive ? "step" : undefined}>
                <ProgressBar
                  label={step}
                  isLabelHidden
                  value={isDone ? 100 : 0}
                  isIndeterminate={isActive}
                />
                <HStack gap={1} vAlign="center">
                  {isDone && <Icon icon="check" size="sm" color="success" />}
                  <Text
                    type="supporting"
                    color={isActive ? "primary" : "secondary"}
                    weight={isActive ? "semibold" : undefined}
                  >
                    {isActive ? `${step} 중` : step}
                  </Text>
                </HStack>
              </li>
            );
          })}
        </ol>

        <Text type="supporting">
          {hasTorch
            ? "torch는 이미지 빌드에 6분 넘게 걸린 적이 있어요."
            : "패키지가 크면 몇 분 더 걸릴 수 있어요."}{" "}
          끝나면 수업 코드가 나타납니다.
        </Text>
      </VStack>
    </Card>
  );
}
