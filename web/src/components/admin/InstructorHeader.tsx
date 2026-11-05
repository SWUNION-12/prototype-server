"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, type ReactNode, useState } from "react";
import { AppHeader } from "@/components/AppHeader";
import { SearchIcon } from "@/components/icons";
import { useToast } from "@/components/Toast";
import { ApiError, auth, INSTRUCTOR_HOME, LOGIN_PATH } from "@/lib/api";

function homeUrl(tab: string | null, q: string): string {
  const params = new URLSearchParams();
  if (tab) params.set("tab", tab);
  if (q) params.set("q", q);
  const query = params.toString();
  return query ? `${INSTRUCTOR_HOME}?${query}` : INSTRUCTOR_HOME;
}

export function InstructorHeader({ actions }: { actions?: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const showToast = useToast();
  const isHome = pathname.replace(/\/$/, "") === INSTRUCTOR_HOME;
  const tab = isHome ? params.get("tab") : null;
  const [query, setQuery] = useState(isHome ? (params.get("q") ?? "") : "");
  const [leaving, setLeaving] = useState(false);

  function change(next: string) {
    setQuery(next);
    if (isHome) router.replace(homeUrl(tab, next.trim()));
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!isHome) router.push(homeUrl(null, query.trim()));
  }

  async function logout() {
    setLeaving(true);
    try {
      await auth.logout();
      window.location.assign(LOGIN_PATH);
    } catch (e) {
      showToast({ body: e instanceof ApiError ? e.message : "로그아웃하지 못했습니다.", type: "error" });
      setLeaving(false);
    }
  }

  return (
    <AppHeader
      home={INSTRUCTOR_HOME}
      search={
        <form onSubmit={submit} role="search">
          <label className="search">
            <SearchIcon />
            <input
              type="search"
              placeholder="수업 검색"
              aria-label="수업 검색"
              value={query}
              onChange={(e) => change(e.target.value)}
            />
          </label>
        </form>
      }
      actions={
        <>
          <button type="button" className="btn btn--sm btn--line" disabled={leaving} onClick={logout}>
            로그아웃
          </button>
          {actions}
        </>
      }
      nav={
        <>
          <Link href={homeUrl(null, query.trim())} aria-current={isHome && tab !== "ended" ? "page" : undefined}>
            진행 중
          </Link>
          <Link href={homeUrl("ended", query.trim())} aria-current={isHome && tab === "ended" ? "page" : undefined}>
            종료됨
          </Link>
        </>
      }
    />
  );
}
