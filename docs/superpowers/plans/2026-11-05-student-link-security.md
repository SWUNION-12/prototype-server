# 학생 주소 보안 Implementation Plan

**Goal:** 학생 화면 주소에서 토큰을 빼 쿠키로 학생을 알아보고, 운영에서는 Caddy가 IDE 요청마다 주인 학생인지 확인해 IDE 비밀번호를 없앤다.

**Spec:** `docs/specs/2026-11-05-student-link-security-design.md`

## Global Constraints

- 학생 쿠키 이름 `LAB_STUDENT`, 값은 세션 토큰, HttpOnly, SameSite=Strict, HTTPS면 Secure, Path `/`, 12시간.
- 학생 API는 `/api/me/**`. 토큰이 주소에 들어가는 학생 API(`/api/s/{token}/**`, `/l/{token}/**`)는 남기지 않는다. `/l/**`는 10분짜리 다운로드 코드만 받는다.
- `/api/me/**` POST는 CSRF(`XSRF-TOKEN` → `X-XSRF-TOKEN`) 검사. `/api/join`, `/api/ide/**`는 제외.
- `lab.ide-auth`: `password`(기본) | `proxy`. proxy일 때만 code-server `--auth none`.
- `GET /api/ide-auth/{port}`: 주인 204, 그 밖 401.
- 토큰, 다운로드 코드, IDE 비밀번호, 컨테이너 키는 로그에 남기지 않는다. 응답 본문에 토큰을 싣지 않는다.
- 화면 문구는 한국어. 서비스 이름 코딩책상, 용어 "수업 코드".
- 커밋하지 않는다. `.env` 값과 비밀번호는 출력하지 않는다. 문서·주석·커밋에 AI 관련 표시를 넣지 않는다.

## Review Focus

1. 재입장으로 토큰이 바뀐 뒤 예전 브라우저: `/api/me` 404 → "다시 입장" 화면.
2. 공유된 `/lab` 주소를 다른 브라우저에서 열기: 쿠키가 없으니 "다시 입장" 화면.
3. 남의 IDE 주소(`/ide/<포트>/`)를 로그인한 다른 학생이 열기: 401.
4. 10분 지난 설치 명령, 재입장 뒤의 설치 명령: 404.
5. 실습 도우미 패널(`/api/ide/**`)은 그대로 동작.

---

### Task S1: 학생 쿠키와 `/api/me` (서버)

- `POST /api/join`이 `LAB_STUDENT` 쿠키를 발급하고 본문은 `{resumed}`만 준다(상태 코드 201/200 그대로).
- `/api/s/{token}/**`를 `/api/me/**`로 옮긴다(목록은 spec 4-1). 쿠키 없음·무효 404 `{error: "입장 정보를 찾을 수 없습니다. 다시 입장해 주세요."}`.
- `POST /api/me/logout` 204, 쿠키 삭제.
- 다운로드 코드: `POST /api/me/local/link?os=macos|windows` → `{diagnoseCommand, installCommand, scriptUrl, zipUrl, expiresAt}`. 코드는 32바이트 이상 무작위, 10분, 메모리 보관, 재입장·학생 삭제·수업 삭제로 무효. `/l/{code}/install.sh|install.ps1|files/{name}|code.zip`이 코드로 세션을 찾는다. 기존 `GET …/local/install`은 이 응답으로 바꾼다.
- `GET /api/me/code.zip`(쿠키).
- SecurityConfig: `/api/me/**` POST에 CSRF, `/api/s/**` 규칙 제거, `/l/**` permitAll 유지.
- 테스트: spec 6절의 서버 항목 중 쿠키·me·CSRF·다운로드 코드. 기존 학생 API 테스트를 새 경로로 옮긴다.

### Task S2: IDE 접근 확인 (서버)

- `lab.ide-auth` 설정(`IDE_AUTH`, 기본 `password`, 그 밖 값은 시작 실패).
- proxy면 학생 컨테이너를 만들 때 실행 인자를 기본 이미지 CMD에서 `--auth password`만 `--auth none`으로 바꾼 것으로 준다. 세션에 만든 방식(`ideAuthMode`)을 저장한다(열 추가, 기존 행 null = password로 본다).
- `GET /api/ide-auth/{port}`: 쿠키 세션의 hostPort가 port이면 204, 아니면 401(짧은 한국어 본문). permitAll, CSRF 무관(GET).
- `GET /api/me`의 `idePassword`는 그 세션이 password 방식일 때만.
- 테스트: 주인 204, 다른 학생 401, 쿠키 없음 401, 숫자 아닌 port 401/400, proxy 컨테이너 인자, password 컨테이너 인자 그대로, idePassword 포함 여부.

### Task W: 학생 화면 (web)

- 입장 성공 → `/lab`(쿼리 없음). `/lab?t=…`로 열리면 주소에서 `t`를 지운다(무시).
- `api.ts` 학생 API를 `/api/me/**`로. POST에 CSRF 헤더(강사 쪽과 같은 방식).
- `/api/me` 404 → 지금의 "다시 입장" 화면.
- IDE 머리말: `idePassword`가 있을 때만 비밀번호와 복사 버튼. 없으면 iframe만.
- 머리말에 "나가기"(logout 뒤 `/join`).
- 내 PC로 이어가기: 패널을 열 때 `POST /api/me/local/link`로 명령을 받고 "10분 동안 쓸 수 있습니다. 지나면 다시 받으세요."와 "다시 받기" 버튼. 종료 화면과 패널의 코드 zip은 `/api/me/code.zip`.
- 확인: `pnpm lint && pnpm typecheck && pnpm build`, `grep -rn "?t=\|/api/s/" web/src` 결과 없음.

### Task C: Caddy와 배포 문서

- `deploy/Caddyfile`의 `/ide/<포트>` 경로에 `forward_auth 127.0.0.1:8080 { uri /api/ide-auth/<포트> }`. Cookie 제거 대상에 `LAB_STUDENT` 추가(빈 값이 아닌 치환, 2.6.2 호환).
- `deploy/lab.env.example`에 `IDE_AUTH=proxy`.
- `docs/deploy.md`: 설정 표, 확인 절(공유 주소 막힘, 남의 IDE 401), 알려진 한계(한 브라우저 한 학생).
- 확인: `caddy:2.6.2` 이미지로 validate, 그리고 forward_auth 대상과 백엔드를 작은 가짜 서버로 띄워 허용·거부·쿠키 제거를 실제로 본다.

### Task E: 확인

- 로컬(password 방식)과 proxy 방식 둘 다: 입장 → `/lab` 주소에 토큰 없음 → 다른 컨텍스트에서 `/lab` 열면 다시 입장 → 재입장 뒤 옛 컨텍스트 다시 입장 → 도움 요청 → 나가기 → 설치 명령 링크 받기와 만료.
- proxy 방식은 로컬에 Caddy(`caddy:2.6.2` 컨테이너, host 네트워크 대신 포트 연결)를 두고 IDE가 비밀번호 없이 열리는지, 다른 학생 쿠키로는 401인지 본다.
