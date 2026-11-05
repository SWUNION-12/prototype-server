"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

// 정적 빌드에는 서버 리다이렉트가 없다. 배포에서는 Spring이 /를 /join으로 보내고, 이 이동은 개발 서버용이다.
export default function RootPage() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/join");
  }, [router]);
  return null;
}
