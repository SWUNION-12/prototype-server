"use client";

import { Badge } from "@astryxdesign/core/Badge";
import { COURSE_BADGE, ENV_BADGE } from "@/lib/display";
import type { CourseStatus, EnvStatus } from "@/lib/types";

export function EnvBadge({ status, prefix }: { status: EnvStatus | null; prefix?: string }) {
  const { label, variant } = ENV_BADGE[status ?? "UNKNOWN"];
  return <Badge variant={variant} label={prefix ? `${prefix} ${label}` : label} />;
}

export function CourseBadge({ status }: { status: CourseStatus }) {
  const { label, variant } = COURSE_BADGE[status];
  return <Badge variant={variant} label={label} />;
}
