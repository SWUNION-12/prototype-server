"use client";

import { createContext, useContext } from "react";
import type { Dashboard, DeleteResult } from "@/lib/types";

type CourseContextValue = {
  courseId: number;
  dashboard: Dashboard;
  refresh: () => Promise<void>;
  // 삭제 응답. 못 지운 자원이 있어 화면에 머물 때, 탭을 오가거나 상단 배너가 읽어도 "삭제됨"을 알 수 있게 위에서 쥔다.
  deleted: DeleteResult | null;
  setDeleted: (r: DeleteResult) => void;
};

const CourseContext = createContext<CourseContextValue | null>(null);

export const CourseProvider = CourseContext.Provider;

export function useCourse(): CourseContextValue {
  const value = useContext(CourseContext);
  if (!value) throw new Error("useCourse는 수업 상세 레이아웃 안에서만 쓸 수 있습니다.");
  return value;
}
