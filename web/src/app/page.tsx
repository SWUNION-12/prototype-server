"use client";

import { Center } from "@astryxdesign/core/Center";
import { Link } from "@astryxdesign/core/Link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

// 정적 빌드에는 서버 리다이렉트가 없다. 배포에서는 Spring이 /를 /join으로 보내고, 이 이동은 개발 서버용이다.
export default function RootPage() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/join");
  }, [router]);
  // 이동 전(또는 서버 리다이렉트 없이 연 경우)에 빈 화면이 되지 않게 링크를 둔다.
  return (
    <Center className="app-root" padding={4}>
      <Link href="/join">수업 코드로 입장하기</Link>
    </Center>
  );
}
