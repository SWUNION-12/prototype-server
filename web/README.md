# 코딩책상 화면

팀이 만든 클라이언트(SWUNION-12/client, 커밋 `39244a7`)를 가져와 우리 서버에 맞게 고친 Next.js 앱입니다.

- 개발: Spring을 8080에 띄우고 `pnpm install && pnpm dev`. `/api`, `/l` 요청은 `API_TARGET`(기본 `http://localhost:8080`)으로 넘어갑니다.
- 빌드: `pnpm build`. 정적 파일이 `out/`에 만들어지고, 배포에서는 Spring이 내보냅니다.
- 검사: `pnpm lint`, `pnpm typecheck`.
