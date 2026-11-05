import { COURSE_BADGE, ENV_BADGE } from "@/lib/display";
import type { CourseStatus, EnvStatus } from "@/lib/types";

export function EnvBadge({ status, prefix, title }: { status: EnvStatus | null; prefix?: string; title?: string }) {
  const { label, variant } = ENV_BADGE[status ?? "UNKNOWN"];
  return (
    <span className={`badge badge--${variant}`} title={title}>
      {prefix ? `${prefix} ${label}` : label}
    </span>
  );
}

export function CourseBadge({ status }: { status: CourseStatus }) {
  const { label, variant } = COURSE_BADGE[status];
  return <span className={`badge badge--${variant}`}>{label}</span>;
}
