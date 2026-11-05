import type { NextConfig } from "next";
import { PHASE_DEVELOPMENT_SERVER } from "next/constants";

// 개발 서버가 /api, /l 요청을 넘길 로컬 Spring. 배포에서는 같은 주소의 Spring이 정적 파일과 API를 함께 내보낸다.
const apiTarget = process.env.API_TARGET ?? "http://localhost:8080";

export default function config(phase: string): NextConfig {
  const base: NextConfig = {
    // Spring이 out/을 그대로 내보낸다. 서버 기능(rewrites 등)은 정적 빌드에서 쓸 수 없다.
    output: "export",
  };
  // rewrites는 output: "export"와 함께 빌드하면 경고가 나므로 개발 서버에서만 켠다.
  if (phase !== PHASE_DEVELOPMENT_SERVER) return base;
  return {
    ...base,
    // 학생 입장은 컨테이너가 뜰 때까지 기다린다. 서버의 헬스 체크 제한이 60초다.
    experimental: { proxyTimeout: 120_000 },
    async rewrites() {
      return [
        { source: "/api/:path*", destination: `${apiTarget}/api/:path*` },
        { source: "/l/:path*", destination: `${apiTarget}/l/:path*` },
      ];
    },
  };
}
