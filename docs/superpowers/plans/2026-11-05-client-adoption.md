# 새 화면(코딩책상) 도입 Implementation Plan

**Goal:** 팀 클라이언트(SWUNION-12/client@39244a7)를 `web/`으로 가져와 우리 서버 API에 맞게 고치고, 정적 빌드로 jar에 넣어 지금 HTML 화면을 대신한다.

**Architecture:** Next.js `output: "export"`로 만든 정적 파일을 Spring이 같은 주소에서 내보낸다. 확장자 없는 경로(`/join`)는 `join.html`로, 동적 경로는 쿼리(`/lab?t=`, `/instructor/course?id=`)로 바꾼다. 강사 화면은 Spring Security 세션 로그인(`/instructor/login`)으로 막고, 화면 코드는 계정을 저장하지 않는다.

**Tech Stack:** Spring Boot 4.1.2 (Java 21 release, Jackson 3), Next.js 16.4.5, React 19.3.0, Astryx 0.7.8, pnpm(로컬 11.17.0), Gradle 9.8.2.

**Spec:** `docs/specs/2026-11-05-client-adoption-design.md`

## Global Constraints

- **서버가 기준이다.** 서버 API는 spec 6절의 필드 3개(`courseStatus`, `latestHelp.classification`, `studentCount`)와 정적 파일 제공·보안 경로 말고는 바꾸지 않는다.
- PIN 평문, PIN 해시, 세션 토큰(본인 것 제외), 컨테이너 키는 어떤 응답·화면에도 나오지 않는다.
- 강사 아이디·비밀번호를 브라우저 저장소(sessionStorage, localStorage, 쿠키)에 두지 않는다. 화면 코드는 `Authorization` 헤더를 만들지 않는다. 서버가 발급하는 HttpOnly 세션 쿠키와 CSRF 쿠키(`XSRF-TOKEN`)만 쓴다(Task 8).
- 모델과 학생 환경에서 온 글(`explanation`, `issues`, `extras`, `summary`, 오류 문구)은 텍스트로만 그린다. `dangerouslySetInnerHTML` 금지.
- 학생 입장 코드는 화면·서버 문구 모두 **"수업 코드"**라고 부른다("초대 코드", "입장 코드" 쓰지 않음). 강사 화면 경로는 **`/instructor`**.
- 서비스 이름은 **코딩책상**. IDE 안 패널 이름 "실습 도우미"는 바꾸지 않는다.
- 화면 문구는 한국어. 클라이언트 디자인(Astryx 컴포넌트, 레이아웃)을 바탕으로 필요한 곳만 고친다.
- `web/`에서 Next.js 코드를 쓰기 전에 `web/AGENTS.md`의 안내대로 `web/node_modules/next/dist/docs/`의 해당 문서를 읽는다(이 Next.js는 학습 데이터와 다르다).
- **커밋하지 않는다.** 사용자가 요청하면 나중에 나눠 커밋한다(맨 아래 커밋 계획). 사용자의 기존 미커밋 변경을 건드리지 않는다.
- `.env` 값, 토큰, 비밀번호, 서버 IP는 출력·기록하지 않는다. 커밋·문서·주석에 작업 도구 관련 표시를 넣지 않는다.

## Review Focus

1. **재입장으로 토큰이 바뀐 옛 탭**: 다음 조회에서 404 → "다시 입장" 화면으로 바뀌고 조회를 멈춘다. (Task 5 단계, Task 7 시나리오 5)
2. **로그인하지 않은 브라우저가 `/instructor/course?id=1`을 바로 연다**: 로그인 페이지(`/instructor/login?next=…`)로 가고, 로그인하면 원래 화면으로 돌아와 정상 동작한다. 화면 도중 강사 API가 401이면 `/instructor/login?next=…`으로 보낸다. (Task 2 테스트, Task 4·8 단계)
3. **쿼리가 없거나 잘못됨**: `/lab`(t 없음), `/instructor/course`(id 없음, 숫자 아님) → 깨지지 않고 안내 화면. (Task 4, 5 단계, Task 7 시나리오 9)
4. **준비 중·실패한 수업**: LOCKING/BUILDING 수업 코드로 입장하면 서버 400 문구가 그대로 보인다. FAILED 수업 대시보드는 `errorSummary`를 보인다. (Task 4, 5 단계)
5. **IDE가 바뀌거나 멈춤**: 강사가 재시작하거나 수업이 종료되면 실습 화면이 새 `ideUrl`로 iframe을 다시 띄우거나 종료 화면으로 바뀐다. (Task 5 단계, Task 7 시나리오 6·7)

## 진행 방식

- 트랙 A(서버): Task 1 → Task 2. 트랙 B(화면): Task 3 → Task 4 → Task 5. **두 트랙은 동시에** 돈다(A는 `src/`와 Gradle, B는 `web/`과 pnpm만 만진다).
- Task 8(강사 로그인, 사용자 결정으로 추가)은 Task 2 뒤, Task 6 앞. Task 6은 그 뒤, Task 7은 마지막.

---

### Task 1: 서버 응답 필드 3개

**Files:**
- Modify: `src/main/java/com/swunion/lab/session/StudentStatusView.java`
- Modify: `src/main/java/com/swunion/lab/help/HelpRequest.java`, `HelpRequestRepository.java`, `HelpResultListener.java`
- Modify: `src/main/java/com/swunion/lab/course/CourseController.java`, `src/main/java/com/swunion/lab/session/SessionRepository.java`
- Test: `StudentControllerTest`, `IdeControllerTest`(키 목록을 정확히 비교하면 갱신), `HelpRequestRepositoryTest`, `CourseControllerTest`, Create `src/test/java/com/swunion/lab/help/HelpResultListenerTest.java`

**Interfaces:**
- Produces: 학생 상태 응답(`GET /api/s/{token}`, `GET /api/ide/status` 공통)에 `courseStatus: "LOCKING"|"BUILDING"|"READY"|"FAILED"|"ENDED"`, `latestHelp.classification: "ENV"|"CODE"|"OUT_OF_SCOPE"|"UNKNOWN"|null`. 수업 응답(목록, 단건, 생성)에 `studentCount: number`.
- `HelpRequest`에 `@Enumerated(EnumType.STRING) Classification classification`(agent 패키지의 enum) + getter. `HelpRequestRepository.updateResult(long id, HelpStatus status, String explanation, boolean escalated, Classification classification)`.
- `CourseController.view(Course c, long studentCount)`. 세는 쿼리는 `SessionRepository`에 둔다(목록은 수업 수만큼 세도 된다).

- [ ] **Step 1: 실패하는 테스트**
  - `StudentControllerTest.statusIncludesCourseStatus`: READY 수업 학생 → `$.courseStatus == "READY"`; 수업을 ENDED로 바꾼 뒤 → `"ENDED"`.
  - `StudentControllerTest.latestHelpCarriesClassification`: classification CODE로 끝난 도움 요청 → `$.latestHelp.classification == "CODE"`; 처리 중인 요청 → `$.latestHelp.classification` 값이 null.
  - `StudentControllerTest.statusNeverCarriesPin`: 응답에 `pin`, `pinHash` 키가 없다(회귀 방지).
  - `HelpResultListenerTest.copiesClassification`: action(outcome RESOLVED, classification ENV) → `updateResult(id, RESOLVED, explanation, false, ENV)` 호출.
  - `HelpResultListenerTest.nullActionGivesNoActionWithoutClassification`: action null → `updateResult(id, NO_ACTION, null, false, null)`.
  - `HelpRequestRepositoryTest.updateResultStoresClassification`: 저장 후 다시 읽으면 classification이 같다.
  - `CourseControllerTest.listIncludesStudentCount`: 학생 2명 수업 → `studentCount == 2`, 학생 없는 수업 → `0`. 단건 조회도 같은 값. 생성 응답은 `0`.
- [ ] **Step 2: 실행해서 실패 확인** — `./gradlew test --tests '*StudentControllerTest' --tests '*HelpResultListenerTest' --tests '*HelpRequestRepositoryTest' --tests '*CourseControllerTest'` → 새 테스트 FAIL
- [ ] **Step 3: 구현** — 위 Interfaces대로. DB는 `ddl-auto: update`라 새 열은 자동으로 붙는다(기존 행은 null).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 이어서 `./gradlew test` 전체 PASS

---

### Task 2: 정적 화면 제공과 강사 경로 보안

**Files:**
- Create: `src/main/java/com/swunion/lab/config/WebConfig.java`
- Modify: `src/main/java/com/swunion/lab/config/SecurityConfig.java`
- Create (테스트용 정적 파일, 각 파일에 구분용 글자 한 줄): `src/test/resources/static/join.html`(`join-page`), `lab.html`(`lab-page`), `admin.html`(`admin-page`), `admin.txt`, `admin/new.html`(`admin-new-page`), `admin/course.html`(`admin-course-page`), `_next/static/app.js`
- Test: Create `src/test/java/com/swunion/lab/config/WebConfigTest.java`, Modify `SecurityConfigTest.java`

**Interfaces:**
- Produces: `GET /join` → `static/join.html`, `GET /instructor/course?id=1` → `static/instructor/course.html`. 마지막 경로 조각에 점(.)이 없을 때만 `<경로>.html`을 찾는다. `GET /` → `/join`으로 리다이렉트.
- 인증 필요: `/api/courses/**`, `/instructor`, `/instructor/**`, `/instructor.html`, `/instructor.txt`(정적 빌드가 만드는 RSC 파일). 기존 `/instructor.html`, `/dashboard.html` 규칙은 Task 6까지 남긴다. 나머지는 지금처럼 permitAll.

- [ ] **Step 1: 실패하는 테스트** (`SecurityConfigTest`의 기존 방식을 따른다)
  - `extensionlessPathServesHtml`: `/join` → 200, 본문에 `join-page`. `/join.html` → 200.
  - `labWithQueryServesLab`: `/lab?t=abc` → 200, `lab-page`.
  - `rootRedirectsToJoin`: `/` → 3xx, `Location`이 `/join`으로 끝난다.
  - `adminPagesNeedInstructor`: 인증 없이 `/instructor`, `/instructor/new`, `/instructor/course?id=1`, `/instructor.html`, `/instructor.txt` → 401이고 `WWW-Authenticate`가 `Basic`으로 시작. 강사 계정으로 → 200, 각 구분 글자.
  - `nextAssetsArePublic`: `/_next/static/app.js` → 200, 인증 없이.
  - `unknownPathIs404`: `/nope` → 404. `/api/s/없는토큰` → 404(정적 대체가 API를 가로채지 않음).
  - `traversalDoesNotBypassAuth`: 인증 없이 `/join/../instructor`, `/%2e%2e/instructor` → 200이 아니다.
- [ ] **Step 2: 실행해서 실패 확인** — `./gradlew test --tests '*WebConfigTest' --tests '*SecurityConfigTest'` → FAIL
- [ ] **Step 3: 구현** — `WebMvcConfigurer`의 리소스 핸들러에 `.html` 대체 리졸버, 뷰 컨트롤러로 `/` 리다이렉트. Spring Boot 기본 정적 처리와 겹치지 않게 한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, `./gradlew test` 전체 PASS

---

### Task 3: 클라이언트 가져오기, 정적 빌드, 경로 바꾸기

**Files:**
- Create: `web/` ← `.superpowers/client-review/repo`의 커밋 `39244a7` (`git -C .superpowers/client-review/repo archive 39244a7 | tar -x -C web`). 그 뒤 `web/mock/`, `web/API_CONTRACT.md`, `web/GEMINI.md`를 지운다.
- Modify: `web/package.json`, `web/next.config.ts`, `web/README.md`(출처 커밋과 실행 방법만 짧게), `web/src/lib/display.ts`, `web/src/app/layout.tsx`
- Move: `web/src/app/lab/[token]/page.tsx` → `web/src/app/lab/page.tsx`; `web/src/app/instructor/courses/[id]/{layout,page,students/page,settings/page}.tsx` → `web/src/app/instructor/course/page.tsx`(+ 필요하면 같은 폴더의 컴포넌트 파일)
- Delete: `web/src/app/instructor/login/`, `web/src/lib/auth.ts`, `web/src/lib/use-credentials.ts`

**Interfaces:**
- Produces: 정적 빌드 결과 `web/out/`에 `join.html`, `lab.html`, `admin.html`, `admin/new.html`, `admin/course.html`, `_next/`.
- 경로: 실습 `/lab?t=<토큰>`, 수업 `/instructor/course?id=<id>`(학생 탭), `/instructor/course?id=<id>&tab=settings`(설정 탭). 쿼리는 `useSearchParams`로 읽고 `Suspense`로 감싼다.
- `web/src/lib/api.ts`: 강사 요청도 일반 `request()`를 쓴다. `Authorization`·`X-Requested-With`를 붙이지 않는다. 401이면 `ApiError(401, "강사 로그인이 필요합니다. 페이지를 새로고침해 로그인해 주세요.")`를 던지고 이동하지 않는다. `verifyLogin` 삭제.
- `SERVICE_NAME = "코딩책상"`, 문서 제목도 "코딩책상".

- [ ] **Step 1: 가져오기** — 위 명령으로 `web/`을 만들고 세 항목을 지운다. `web/.gitignore`가 `node_modules`, `.next`, `out`을 무시하는지 확인한다.
- [ ] **Step 2: 설치** — `cd web && pnpm install --frozen-lockfile`. 로컬 pnpm(11.17.0)이 `packageManager` 필드나 잠금 파일 형식 때문에 거부하면, 의존성 버전은 그대로 두고 `packageManager`를 로컬 pnpm에 맞추거나 잠금 파일을 다시 만든다(어느 쪽을 했는지 보고).
- [ ] **Step 3: 설정** — `package.json` scripts: `dev: "next dev"`, `build: "next build"`, `lint: "eslint"`, `typecheck: "tsc --noEmit"`. `concurrently` 제거. `next.config.ts`: `output: "export"`, `/api`·`/l` 넘기기(rewrites)는 개발 모드에서만, 기본 대상 `http://localhost:8080`(`API_TARGET`로 바꿀 수 있음).
- [ ] **Step 4: 경로와 로그인 정리** — 위 Move/Delete. 관리 레이아웃의 로그인 확인 로직을 지운다. 페이지 안 링크(`/instructor/courses/${id}/students` 등)를 새 경로로 바꾼다. 루트 `page.tsx`는 `/join`으로 보내는 클라이언트 이동만 남긴다(배포에서는 서버가 리다이렉트).
- [ ] **Step 5: 확인** — `pnpm lint && pnpm typecheck && pnpm build` 통과. `ls web/out web/out/instructor`에 위 파일들이 있다. `grep -rn "sessionStorage\|admin/login\|Authorization" web/src` → 결과 없음.

---

### Task 4: 강사 화면을 우리 API에 맞추기

**Files:**
- Modify: `web/src/lib/types.ts`, `web/src/lib/api.ts`(강사 부분), `web/src/app/instructor/page.tsx`, `web/src/app/instructor/new/page.tsx`, `web/src/app/instructor/course/**`, 필요하면 `web/src/components/instructor/*`
- 참고(읽기만): `src/main/resources/static/dashboard.html`, `instructor.html`(버튼을 켜고 끄는 규칙과 문구), `LifecycleController`, `LifecycleService`, `DashboardView`

**Interfaces:**
- Consumes: Task 1의 `studentCount`. Task 3의 경로와 `request()`.
- `types.ts`: `Course`에서 `capacity` 삭제(`studentCount` 유지). `StudentRow.pin` → `pinSet: boolean`. `Dashboard.course`에서 `capacity` 삭제. 추가: `ResumeResult = { started; unhealthy; recreatedEmpty; failed }`(모두 number), `DeleteResult = { removedContainers: number; removedVolumes: number; failures: string[] }`.
- `api.ts` 강사: `update` 삭제. `resume(id) → ResumeResult`(POST `/api/courses/{id}/resume`), `deleteCourse(id, confirmName) → DeleteResult`(DELETE `/api/courses/{id}`, 본문 `{confirmName}`), `restart → { ok: boolean }`, `resetPin → void`(204), `remove(id, sessionId) → DeleteResult`.

- [ ] **Step 1: 타입과 API** — 위대로 고친다. `pnpm typecheck`가 가리키는 곳을 따라 화면을 고친다.
- [ ] **Step 2: 학생 목록** — PIN 칸은 "설정됨"/"미설정" 글자만. PIN 초기화 확인 문구: "PIN을 지웁니다. 학생이 다음 입장 때 새 PIN을 정합니다." 성공 토스트도 같은 뜻. 재시작이 `{ok:false}`면 "재시작하지 못했습니다" 오류 토스트. 삭제 응답의 `failures`가 있으면 토스트에 함께 보인다. 409 등 오류는 서버 문구를 그대로 보인다.
- [ ] **Step 3: 설정 탭** — 정원·이름 변경 폼을 지우고 수업 정보(Python, 패키지, 입장 코드, 상태)와 **수업 종료 / 수업 재개 / 수업 삭제**를 둔다. 언제 켜지는지는 `dashboard.html`의 규칙을 따른다. 삭제는 수업 이름을 똑같이 입력해야 버튼이 켜지고, 성공하면 `/instructor`으로 간다. 재개 결과(`started`, `unhealthy`, `recreatedEmpty`, `failed`)를 한 줄로 보인다.
- [ ] **Step 4: 목록과 만들기** — 수업 카드에 학생 수(`studentCount`). Python 선택지는 3.8~3.14만(서버 규칙 `^3\.[0-9]{1,2}(\.[0-9]{1,2})?$`에 맞는 값). 만들기 성공 → `/instructor/course?id=<id>`. 입장 링크 복사는 `${location.origin}/join?code=<코드>`.
- [ ] **Step 5: 이상한 입력** — `/instructor/course`에 `id`가 없거나 숫자가 아니면 "수업을 찾을 수 없습니다" 안내와 목록으로 가는 버튼. 404도 같은 안내. FAILED 수업은 `errorSummary`를 보인다. API 401이면 Task 3의 안내 문구를 배너로 보인다.
- [ ] **Step 6: 확인** — `pnpm lint && pnpm typecheck && pnpm build` 통과. `grep -rn "capacity\|\.pin\b\|courses.update" web/src` → 결과 없음.

---

### Task 5: 학생 화면을 우리 API에 맞추기

**Files:**
- Modify: `web/src/lib/types.ts`, `web/src/lib/api.ts`(학생 부분), `web/src/lib/display.ts`, `web/src/app/join/page.tsx`, `web/src/app/lab/page.tsx`, `web/src/components/lab/IdeFrame.tsx`, `HelpPanel.tsx`, `LocalPanel.tsx`
- 참고(읽기만): `src/main/resources/static/join.html`, `student.html`(문구, 작업 공간 유실 안내, 복사 대체 처리), `StudentController`, `StudentStatusView`, `LocalService`

**Interfaces:**
- Consumes: Task 1의 `courseStatus`, `latestHelp.classification`. Task 3의 `/lab?t=`.
- `types.ts`: `Me`에 `courseStatus: CourseStatus`(필수), `issuesText: string[]`, `workspaceLost: boolean`. `LatestHelp.classification: Classification | null`.
- `api.ts`: `student.join → { token: string; resumed: boolean }`.

- [ ] **Step 1: 입장** — `?code=`로 입장 코드를 채운다. 성공 → `/lab?t=<토큰>`, 재입장(`resumed`)이면 `&resumed=1`을 붙인다. 400·403(남은 횟수)·409·429·500 문구는 서버가 준 그대로. 입장은 1분 넘게 걸릴 수 있으니 그동안 버튼을 막고 안내 문구를 보인다.
- [ ] **Step 2: 실습 화면 상태** — `t`가 없거나 응답이 404면 "다시 입장" 화면(입장 페이지로 가는 버튼)을 보이고 조회를 멈춘다. `resumed=1`이면 "이전 작업 공간으로 돌아왔습니다" 안내를 한 번. `workspaceLost`면 `student.html`과 같은 뜻의 경고 배너. 환경 상태 툴팁은 서버의 `issuesText`를 줄바꿈으로 잇는다(`describeIssue`가 안 쓰이면 지운다).
- [ ] **Step 3: IDE** — 숨은 폼 자동 로그인을 지운다. `<iframe src={ideUrl} key={ideUrl}>`로 띄우고(주소가 바뀌면 다시 뜬다), 머리말에 "IDE 비밀번호"와 복사 버튼, "새 탭에서 열기"(`target="_blank" rel="noopener noreferrer"`)를 둔다. 복사 API를 못 쓰면 `student.html`처럼 값을 직접 보이게 한다. `ideUrl`이 `http(s)://…/`가 아니면 지금처럼 오류 배너.
- [ ] **Step 4: 종료 상태** — `courseStatus === "ENDED"`면 IDE 대신 종료 안내와 코드 zip 버튼(`/l/<토큰>/code.zip`). 도움 요청 버튼은 막는다.
- [ ] **Step 5: 도움 패널** — 상태 `PROCESSING`, `EXPLAINED`, `RESOLVED`, `ESCALATED`, `NO_ACTION`을 모두 그린다. `classification`이 null이면 뱃지 없음. 설명은 `"\n\n확인할 곳: "`로 나눠 "확인할 곳" 상자. 409(처리 중, 종료된 수업) 문구를 보인다.
- [ ] **Step 6: 내 PC로 이어가기** — 서버 응답(`diagnoseCommand`, `installCommand`, `scriptUrl`, `zipUrl`, 검증의 `message`)과 맞는지 확인만 하고, 다르면 고친다.
- [ ] **Step 7: 확인** — `pnpm lint && pnpm typecheck && pnpm build` 통과. `grep -rn "auto-login\|rejoined\|dangerouslySetInnerHTML" web/src` → 결과 없음.

---

### Task 8: 강사 로그인 페이지와 세션 로그인 (사용자 결정으로 추가)

Spec 6-1절이 기준이다.

**Files:**
- Modify: `src/main/java/com/swunion/lab/config/SecurityConfig.java` (HTTP Basic 끄기, 세션 로그인, CSRF, 옛 `/instructor.html`·`/dashboard.html` 규칙 삭제)
- Create: 로그인·로그아웃·확인 처리에 필요한 클래스(`config` 또는 `auth` 패키지)
- Modify: `src/main/resources/application.yml` (세션 유지 8시간, 세션 쿠키 SameSite=Lax·HttpOnly, forward headers)
- Modify: Basic 인증으로 강사 API를 부르던 테스트들(로그인된 사용자로 바꾼다), `SecurityConfigTest`, Task 2의 `WebConfigTest`
- Create: `web/src/app/instructor/login/page.tsx` (upstream `39244a7`의 같은 파일 디자인을 살린다: `git -C .superpowers/client-review/repo show 39244a7:src/app/instructor/login/page.tsx`)
- Modify: `web/src/lib/api.ts`(강사 요청에 CSRF 헤더, 401이면 로그인 페이지로), 강사 화면 머리말(로그아웃)

**Interfaces:**
- `POST /api/auth/login` (application/x-www-form-urlencoded `username`, `password`) → 204 / 401 `{error}`. `POST /api/auth/logout` → 204. `GET /api/auth/me` → 200 `{username}` / 401 `{error}`.
- 로그인 전: `/api/courses/**`·`/api/auth/me` → 401 JSON, `WWW-Authenticate` 없음. `/instructor`, `/instructor/**`(단 `/instructor/login`, `/instructor/login.html`, `/instructor/login.txt`, `/instructor/login/**` 제외) → 302 `/instructor/login?next=<인코딩한 원래 경로와 쿼리>`.
- CSRF: `CookieCsrfTokenRepository`(HttpOnly 아님) + 헤더 `X-XSRF-TOKEN`. 제외: `/api/join`, `/api/s/**`, `/l/**`, `/api/ide/**`. 로그인 요청도 CSRF를 검사한다. 화면이 처음 열릴 때 쿠키가 생기도록 한다(로그인 페이지는 `GET /api/auth/me`를 먼저 불러도 된다).
- 화면: `request()`가 POST/PUT/PATCH/DELETE에 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN`으로 붙인다. 강사 요청이 401이면 `/instructor/login?next=<지금 경로>`로 이동. 로그인 페이지는 `next`가 `/instructor`으로 시작하고 `//`로 시작하지 않을 때만 따르고, 아니면 `/instructor`. 이미 로그인돼 있으면 바로 `next`로.

- [ ] **Step 1: 실패하는 서버 테스트**
  - `loginWithRightPasswordGivesSession`: 맞는 계정 → 204, 그 세션으로 `GET /api/courses` 200, `GET /api/auth/me` → `username`.
  - `loginWithWrongPasswordIs401`: 401, `$.error == "아이디 또는 비밀번호를 확인해 주세요."`, 세션으로 `/api/courses` 401.
  - `apiWithoutLoginIs401WithoutBasicChallenge`: `/api/courses` 401, `WWW-Authenticate` 헤더 없음, 본문에 `error`.
  - `basicAuthNoLongerWorks`: Basic 헤더만 보낸 `/api/courses` → 401.
  - `adminPageRedirectsToLogin`: `/instructor/course?id=1` → 302, `Location`이 `/instructor/login?next=`로 시작하고 원래 경로를 담는다. `/instructor/login` → 200(공개).
  - `logoutEndsSession`: 로그아웃 204 뒤 같은 세션으로 `/api/courses` 401.
  - `instructorPostNeedsCsrf`: 로그인된 세션으로 CSRF 헤더 없이 `POST /api/courses/{id}/end` → 403, 헤더를 붙이면 403이 아니다.
  - `studentPathsSkipCsrf`: CSRF 없이 `POST /api/join`, `POST /api/s/{token}/help`, `POST /api/ide/help`가 403이 아니다(기존 동작 그대로).
  - `sessionIdChangesOnLogin`: 로그인 전 세션 ID와 로그인 후 세션 ID가 다르다.
- [ ] **Step 2: 실행해서 실패 확인** — `./gradlew test --tests '*SecurityConfigTest' --tests '*Auth*'` → FAIL
- [ ] **Step 3: 서버 구현** — 위 Interfaces. 강사 API를 Basic으로 부르던 기존 테스트를 고친다.
- [ ] **Step 4: 화면 구현** — 로그인 페이지, CSRF 헤더, 401 이동, 로그아웃. Task 4가 넣은 401 배너는 이동 전 잠깐 보이거나 이동이 막혔을 때의 안내로 둔다.
- [ ] **Step 5: 확인** — `./gradlew test` 전체 PASS. `web/`에서 `pnpm lint && pnpm typecheck && pnpm build` 통과. `grep -rn "sessionStorage\|localStorage\|Authorization" web/src` → 결과 없음.

---

### Task 6: jar에 넣기, 옛 HTML 지우기, 문서

**Files:**
- Modify: `build.gradle`
- Delete: `src/main/resources/static/join.html`, `instructor.html`, `dashboard.html`, `student.html`
- (옛 HTML 보안 규칙은 Task 8이 지운다)
- Modify: `src/main/java/com/swunion/lab/check/IssueTexts.java`, `docker/lab-base/extension/swunion-helper/lib/view-model.js`(주석의 `student.html` → 새 실습 화면)
- Modify: `docs/deploy.md`(로컬 준비물에 Node 20.9 이상과 pnpm, 확인 항목 `/join` 200·`/instructor` → 로그인 페이지·`/api/courses` 401, 화면 주소, 알려진 한계에서 "Basic 인증·CSRF 꺼짐"을 "로그인 시도 횟수 제한 없음"으로), `docs/demo-checklist.md`(주소, 로그인)

**Interfaces:**
- Gradle: `webInstall`(Exec, `web/`에서 `pnpm install --frozen-lockfile`, 입력 `package.json`·`pnpm-lock.yaml`·`pnpm-workspace.yaml`, 출력 `node_modules`), `webBuild`(Exec, `pnpm build`, 입력 `src`·`public`·설정 파일들, 출력 `out`), `bootJar`가 `webBuild` 출력을 `BOOT-INF/classes/static`에 담는다. `test`, `compileJava`는 웹 빌드에 기대지 않는다.

- [ ] **Step 1: Gradle** — 위 작업을 더한다.
- [ ] **Step 2: 옛 HTML 삭제** — 파일 4개 삭제. 보안 규칙에 남은 것이 없는지 확인한다.
- [ ] **Step 2-1: 용어 "수업 코드"** — `web/src`의 "초대 코드", "입장 코드"를 "수업 코드"로 바꾼다(입력칸 이름, 안내 문구, 복사 토스트, 진행 안내, 설정 탭, 다시 입장 안내). 서버 `SessionService`의 입장 실패 문구도 "수업 코드를 확인해 주세요. 코드가 없거나 아직 준비되지 않은 수업입니다."로 바꾸고, 이 문구를 단언하는 테스트를 고친다. "입장 링크"는 그대로 둔다. 확인: `grep -rn "초대 코드\|입장 코드" web/src src/main/java` → 결과 없음.
- [ ] **Step 3: 주석과 문서** — 위 파일들. 문서는 `stop-slop` 문체(짧고 구체적으로).
- [ ] **Step 4: 확인**
  - `./gradlew test` PASS (pnpm 없이 돈다는 것도 확인: 웹 작업이 실행 목록에 없음)
  - `./gradlew bootJar` 성공, `unzip -l build/libs/lab-0.0.1-SNAPSHOT.jar | grep 'BOOT-INF/classes/static/'`에 `join.html`, `lab.html`, `admin.html`, `admin/new.html`, `admin/course.html`, `_next/`가 있다
  - 빈 임시 폴더에서 `java -jar <jar> --server.port=18080` → `curl` `/join` 200, `/` 3xx → `/join`, `/instructor` 3xx → `/instructor/login?next=…`, `/instructor/login` 200, `/api/courses` 401(`WWW-Authenticate` 없음) → 서버를 끈다

---

### Task 7: 실제 브라우저로 끝까지 확인

로컬 Spring(Task 6 jar, 8080) + Docker, 헤드리스 Chrome(`.superpowers/hide-icons/node_modules`의 playwright-core와 설치된 chrome-headless-shell). 스크립트와 캡처는 `.superpowers/c1/`(커밋하지 않음). 실패하면 원인 파일을 고치고 다시 돈다.

**사용자와 동시에 한다.** 서버를 띄우면 바로 사용자에게 주소(`/instructor`, `/join`)를 알리고, 사용자가 직접 써 보는 동안 자동 시나리오를 돌린다. 자동 시나리오가 만드는 수업은 이름에 `e2e-`를 붙여 사용자의 수업과 구분하고, 정리할 때 `e2e-` 수업만 지운다. 사용자가 찾은 문제는 자동 결과와 같은 목록에 모아 함께 고친다. 팀 서버 배포는 사용자가 따로 허락할 때만 한다.

- [ ] **시나리오**
  1. 강사(계정 넣은 컨텍스트) `/instructor` → 수업 만들기(Python 3.12, `requests`) → 진행 표시 → READY → 입장 코드
  2. 학생(새 컨텍스트) `/join?code=<코드>` → 이름·PIN → `/lab?t=…` → 환경 상태, IDE iframe(`src`가 `ideUrl`), 비밀번호 보임
  3. 학생 도움 요청(`ModuleNotFoundError: No module named 'rich'`) → 결과 카드가 처리 중이 아닌 상태로 바뀜
  4. 강사 `/instructor/course?id=` → 학생 보임, PIN "설정됨"
  5. 다른 컨텍스트에서 같은 이름·PIN으로 재입장 → 옛 탭이 "다시 입장" 화면으로 바뀜
  6. 강사: PIN 초기화(안내 토스트, PIN "미설정"), 재시작(실습 화면 iframe 다시 뜸), 학생 삭제(목록에서 사라짐)
  7. 수업 종료 → 학생 화면 종료 상태와 zip 버튼 → 재개 → READY → 수업 삭제(이름 입력) → `/instructor` 목록에 없음
  8. 로그인 안 한 컨텍스트 `/instructor/course?id=<id>` → 로그인 페이지 → 틀린 비밀번호 안내 → 맞는 비밀번호 → 원래 화면 → 로그아웃 → 로그인 페이지
  9. `/lab`(t 없음), `/instructor/course?id=abc` → 안내 화면, 콘솔 오류 없음
- [ ] 끝나면 만든 수업을 지우고, 단계별 캡처 경로와 결과를 보고한다.

---

## 커밋 계획 (사용자가 요청할 때)

1. `chore: import client app from SWUNION-12/client` — `39244a7` 원본에서 `mock/`, `API_CONTRACT.md`, `GEMINI.md`만 뺀 상태. 원본은 `.superpowers/client-review/repo`에서 다시 꺼내 먼저 커밋하고, 그 위에 지금 `web/`을 덮어 다음 커밋들로 나눈다.
2. `build: export web app as static files` (Task 3)
3. `feat: adapt instructor screens to server API` (Task 4)
4. `feat: adapt student screens to server API` (Task 5)
5. `feat: add course status, help classification, student count to responses` (Task 1)
5-1. `feat: add instructor login page with session auth and CSRF` (Task 8, 서버와 화면을 나눠 커밋해도 된다)
6. `feat: serve web app and protect instructor pages` (Task 2)
7. `build: bundle web app into boot jar` + `refactor: remove legacy HTML pages` + `docs: update deploy guide for web app` (Task 6)
