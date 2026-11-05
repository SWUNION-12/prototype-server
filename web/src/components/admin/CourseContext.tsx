"use client";

import { createContext, useContext } from "react";
import type { Dashboard } from "@/lib/types";

type CourseContextValue = {
  courseId: number;
  dashboard: Dashboard;
  refresh: () => Promise<void>;
};

const CourseContext = createContext<CourseContextValue | null>(null);

export const CourseProvider = CourseContext.Provider;

export function useCourse(): CourseContextValue {
  const value = useContext(CourseContext);
  if (!value) throw new Error("useCourse는 수업 상세 레이아웃 안에서만 쓸 수 있습니다.");
  return value;
}
