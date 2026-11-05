"use client";

import { usePathname, useRouter } from "next/navigation";
import { type ReactNode, useEffect } from "react";
import { useCredentials } from "@/lib/use-credentials";

const LOGIN = "/admin/login";

export default function AdminLayout({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const credentials = useCredentials();
  const isLogin = pathname === LOGIN;
  const mustLogin = !isLogin && credentials === null;

  useEffect(() => {
    if (mustLogin) router.replace(LOGIN);
  }, [mustLogin, router]);

  if (!isLogin && !credentials) return null;
  return children;
}
