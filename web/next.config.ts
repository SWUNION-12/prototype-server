import type { NextConfig } from "next";

// /api, /l 요청을 넘길 곳. 개발 중에는 목업 서버(mock/server.mjs), 서버가 명세를 구현하면 Spring 주소로 바꾼다.
const apiTarget = process.env.API_TARGET ?? "http://localhost:8081";

const nextConfig: NextConfig = {
  // 학생 입장은 컨테이너가 뜰 때까지 기다린다. 서버의 헬스 체크 제한이 60초다.
  experimental: { proxyTimeout: 120_000 },
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${apiTarget}/api/:path*` },
      { source: "/l/:path*", destination: `${apiTarget}/l/:path*` },
    ];
  },
};

export default nextConfig;
