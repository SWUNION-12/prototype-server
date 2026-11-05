# Review backlog (C1)

## Task 1: 서버 응답 필드 3개 (review)
- [Minor] src/test/java/com/swunion/lab/course/CourseControllerTest.java:345 — "백그라운드 발급이 실제로 돌지 않게 막는다" 주석이 새로 끼워 넣은 `@Autowired CourseRepository courses` 위에 남아 `@MockitoBean provisioner`와 떨어졌다 — 새 필드 두 개를 주석 위로 옮기거나 주석을 `provisioner` 바로 위로 되돌린다
- [Minor] src/test/java/com/swunion/lab/help/HelpResultListenerTest.java:522 — `org.mockito.ArgumentMatchers.eq` 완전 한정 이름이 줄마다 반복된다(같은 파일 위쪽은 static import 사용) — `eq` static import로 정리
- [Minor] src/test/java/com/swunion/lab/ide/IdeControllerTest.java:166 — IDE 패널 상태 API는 `courseStatus`만 검증하고 `latestHelp.classification`은 검증하지 않는다(공유 뷰라 StudentControllerTest가 간접 보장) — 도움 요청이 있는 IDE 상태 케이스에 classification 한 줄 추가
- [Minor] src/main/java/com/swunion/lab/course/CourseController.java:42 — 목록이 수업마다 count 쿼리를 낸다(작업 지시가 허용, N+1) — 수업이 많아지면 `group by course_id` 한 번으로 바꾼다
- [Minor] src/test/java/com/swunion/lab/session/StudentControllerTest.java:753 — statusNeverCarriesPin은 구현 변경 없이도 통과하는 회귀 방지 테스트라 현재 뷰가 pin을 싣지 않는 것만 확인한다(작업 지시 지정) — 별도 조치 불필요, 참고
- [Minor] src/test/java/com/swunion/lab/session/StudentControllerTest.java:658 — `com.swunion.lab.agent.Classification` import가 course 패키지 import 사이에 끼어 알파벳 순이 깨졌다 — agent import를 course 앞으로 이동

## Task 3: 클라이언트 가져오기, 정적 빌드, 경로 바꾸기 (review)
- [Important] web/src/app/admin/course/page.tsx:92 — 401이 usePoll의 stopOn에 없어(404만 있음) 로그인 창을 취소하면 3초(최대 30초 백오프)마다 강사 API를 다시 불러 브라우저 로그인 창이 반복해서 뜬다. 또 data가 이미 있으면(139행 `data ? … : error` 순서) 화면 도중 401 안내가 아예 안 보인다 — stopOn에 401 추가하고, data가 있어도 401이면 "새로고침해 로그인" Banner를 위에 보인다(Task 4에서 처리 가능)
- [Important] web/src/app/admin/page.tsx:60 — 같은 문제: `usePoll(courses.list, 3000)`에 stopOn이 없고 76행이 `error != null && !list`일 때만 오류를 보이므로, 목록을 한 번 받은 뒤 계정이 사라지면 401 안내 없이 반복 요청만 한다 — 401에서 조회를 멈추고 안내 Banner를 data와 무관하게 보인다
- [Minor] web/src/app/lab/page.tsx:158 — `<Suspense>`에 fallback이 없어 정적 HTML(lab.html, admin/course.html)은 JS가 붙기 전까지 빈 화면이다 — `<Suspense fallback={<Spinner label="불러오는 중" />}>`
- [Minor] web/src/app/admin/course/page.tsx:184 — 위와 동일(fallback 없음) — 같은 fallback 추가
- [Minor] web/src/app/page.tsx:7 — 루트가 null만 그려서 Spring 리다이렉트 없이 열면 빈 화면 — 서버 리다이렉트가 보장되면 그대로 두되 "입장 페이지로" 링크 정도를 그린다
- [Minor] web/pnpm-lock.yaml — pnpm 12가 `@pnpm/exe@12.16.1`과 플랫폼별 optionalDependencies를 잠금 파일에 새로 적었다(concurrently 제거 외 변화) — 의도한 변화인지 확인하고, CI에서 다른 pnpm이면 `--frozen-lockfile`이 깨지는지 한 번 본다
- [Minor] (Spring 작업 확인) 정적 내보내기는 `/lab`, `/admin/course`, `/join`, `/admin`, `/admin/new`를 `.html`로 만들고, 클라이언트 이동·prefetch는 `/lab.txt`, `/admin/course.txt`, `__next.*.txt`도 요청한다 — Spring이 확장자 없는 경로와 `.txt`를 모두 내보내는지(`/admin/**` 아래 .txt는 Basic 보호, `/lab.txt`·`/join.txt`는 공개) 통합 검증에서 확인

## Task 4: 강사 화면을 우리 API에 맞추기 (review)
- [Minor] web/src/app/admin/course/SettingsTab.tsx:226 — 삭제 응답에 failures가 있어 화면에 머물 때 대시보드 데이터가 삭제 전 값(ENDED)으로 남아 page.tsx:82 배너가 "설정 탭에서 수업을 다시 열 수 있습니다"를 계속 보인다(재개 버튼은 잠겨 있어 모순) — 삭제 완료 뒤에는 StatusBanner를 "삭제된 수업"으로 바꾸거나 숨긴다
- [Minor] web/src/lib/packages.ts:23 — Python 3.8·3.9는 이전 시안의 uv 설치 가능 목록(3.10 이상)에 없었고 uv가 실제로 해석하는지 확인하지 못했다(작업 중 결정, 서버 정규식은 허용) — 통합 검증(Task 7)에서 3.8·3.12 수업을 한 번씩 만들어 FAILED로 끝나지 않는지 본다
- [Minor] web/src/app/admin/course/page.tsx:72 — 화면 문구가 "수업"과 "프로젝트"(page.tsx:151 탭 "프로젝트 설정", admin/page.tsx:70, admin/new/page.tsx:188·200·225·243)를 섞어 쓴다. 한 FAILED 배너 안에서도 "새 프로젝트"와 "이 수업"이 같이 나온다 — 한쪽(수업)으로 통일
- [Minor] web/src/app/admin/course/SettingsTab.tsx:140 — 재개 중 3초 폴링이 계속 돌고, 그 사이 상태가 READY로 바뀌면 재개 결과 배너와 요청 진행 문구가 함께 보일 수 있다(동작에는 문제 없음) — 필요하면 재개 중에는 폴링을 늦춘다

## Task 5: 학생 화면을 우리 API에 맞추기 (review)
- [Minor] web/src/components/lab/LocalPanel.tsx:24 — 진단·설치 명령의 CodeBlock 복사 버튼은 Clipboard API만 쓴다고 보여(리포트 우려) 새 copyText 대체 처리를 타지 않는다. 일반 http 접속이면 조용히 실패한다 — 운영이 https면 그대로 두되, http 접속을 허용한다면 CodeBlock에 onCopy 대체 처리를 달거나 실패 시 선택 안내를 보인다
- [Minor] web/src/components/lab/LocalPanel.tsx:19 — isSafeHref의 `^\/[^\s/]\S*$`가 `/\evil.com`을 통과시킨다(브라우저는 `\`를 `/`로 읽어 프로토콜 상대 주소가 됨). 서버가 만든 값이라 실제 위험은 낮다 — `[^\s/\\]`로 막는다
- [Minor] web/src/lib/clipboard.ts:25 — execCommand 대체 경로에서 임시 textarea를 지우면 포커스가 body로 빠져 복사 버튼에서 키보드 포커스가 사라진다 — 복사 전 document.activeElement를 저장해 두었다가 복원한다
- [Minor] web/src/components/lab/IdeFrame.tsx:16 — copyState가 password/ideUrl이 바뀌어도 "done"/"failed"로 남고, "복사됨" 표시에 aria-live가 없다 — 비밀번호가 바뀌면 idle로 되돌리고 "복사됨" 문구에 role="status"를 단다
- [Minor] web/src/app/lab/page.tsx:88 — 환경 불일치 문구가 Tooltip(호버)에만 있어 터치 기기에서는 볼 수 없고, 여러 줄 pre-wrap 표시는 화면에서 확인되지 않았다(리포트) — Task 7에서 실제로 확인하고, 모바일을 쓴다면 본문에 접이식 목록으로도 보인다

## Task 2: 정적 화면 제공과 강사 경로 보안 (review)
- [Minor] src/test/java/com/swunion/lab/config/WebConfigTest.java:67 — `/api/s/없는토큰` → 404는 컨트롤러가 받았을 때와 정적 대체가 가로챘을 때(정적 파일 없음 → 404) 결과가 같아, 주석이 말하는 "컨트롤러가 먼저 받는다"를 검증하지 못한다 — `handler().handlerType(<학생 컨트롤러>.class)`를 단언하거나, 컨트롤러만 만드는 응답(예: 다른 메서드의 405, 컨트롤러 본문)을 확인한다
- [Minor] src/test/java/com/swunion/lab/config/SecurityConfigTest.java:105 — `/ADMIN`, `/Admin/New`, `/admin.HTML`, `/ADMIN.txt`는 "200 아님"만 보므로, 대소문자를 가리는 파일 시스템(Linux CI, jar)에서는 매처가 대소문자를 가려도 404로 통과한다. 대소문자 무시 매처를 고정하지 못한다 — 이 네 경로는 "인증 요구"(지금은 401, Task 8 뒤에는 401 또는 /admin/login 리다이렉트)를 직접 단언하는 헬퍼로 바꾼다
- [Minor] src/test/java/com/swunion/lab/config/SecurityConfigTest.java:78 — HEAD·OPTIONS 요청과 중첩 RSC 파일(`admin/__next.admin.__PAGE__.txt`, `admin/course.txt`, `admin/course/__next.admin.course.__PAGE__.txt`, 실제 export에 있음)을 다루는 테스트가 없다. 매처는 메서드를 가리지 않고 `/admin/**`가 덮으므로 동작은 맞다 — 고정 파일 하나(`admin/course/__next.x.txt`)를 더하고, 인증 없이 HEAD `/admin`, OPTIONS `/admin`, GET 중첩 .txt가 200이 아님을 단언한다
- [Minor] src/main/java/com/swunion/lab/config/SecurityConfig.java (diff 기준 authorizeHttpRequests) — (Task 8 연결) `/admin/**`가 `/admin/login`, `/admin/login.html`, `/admin/login.txt`, `/admin/login/__next.*.txt`도 덮는다. 또 `next`를 "`/admin`으로 시작"으로만 검사하면 인증 없는 RSC 요청(`/admin.txt?_rsc=…`, `/admin/course.txt`)이 저장·전달되어 로그인 뒤 RSC 원문 화면으로 갈 수 있다 — Task 8에서 같은 대소문자 무시 빌더로 `/admin/login`, `/admin/login.*`, `/admin/login/**` permitAll을 `/admin/**` 규칙보다 앞에 두고, 로그인 리다이렉트의 next는 RSC 요청(`RSC: 1` 헤더 또는 마지막 조각에 점)이면 버리거나 확장자 없는 경로로 바꾼다

## Task 8: 강사 로그인 페이지와 세션 로그인 (review)
- [Minor] src/main/java/com/swunion/lab/auth/AuthResponses.java:83 — handle()이 모든 AccessDeniedException에 CSRF 문구("보안 확인 값이 맞지 않습니다")를 보낸다. 지금 규칙(로그인 여부만)에서는 맞지만 역할 규칙이 하나라도 생기면 권한 거부가 CSRF 오류로 보인다 — `e instanceof CsrfException`일 때만 CSRF 문구, 아니면 일반 403 문구
- [Minor] src/main/resources/application.yml:23 — `timeout: 8h`는 마지막 요청 뒤 유지 시간이고 대시보드가 3초마다 조회하므로 탭을 열어 둔 공용 PC에서는 세션이 끝나지 않는다(spec 6-1 "유지 시간 8시간"을 어느 쪽으로 읽을지의 문제) — 절대 상한이 필요하면 로그인 시각을 세션에 넣고 필터에서 8시간 뒤 무효로 하고, 아니면 spec에 "마지막 요청 뒤 8시간"이라고 적는다
- [Minor] src/main/resources/application.yml:18 — 루프백이 아닌 곳에서 온 X-Forwarded-Proto를 무시하는지는 자동·수동 어느 쪽으로도 확인하지 않았다(번들 Tomcat 11.0.27 RemoteIpValve는 CIDR을 지원하고, 루프백 신뢰가 동작했으니 CIDR로 읽힌 것은 맞다) — 배포 서버에서 학생 컨테이너로 `X-Forwarded-Proto: https`를 붙여 `/api/auth/me`를 불러 Set-Cookie에 Secure가 없는지 한 번 확인한다

## Task 6: jar에 넣기, 옛 HTML 지우기, 문서 (review)
- [Minor] src/test/java/com/swunion/lab/ide/IdeControllerTest.java:69 — 일괄 치환 흔적으로 주석이 "수업 수업 코드"가 됨 — "수업 코드"로 고친다
- [Minor] src/test/java/com/swunion/lab/config/WebConfigTest.java:24 — 주석이 "main의 옛 join.html을 가린다"고 하지만 main의 static/은 이번에 지워져 가릴 것이 없다 — 주석을 "테스트용 고정 파일"로 줄인다
- [Minor] build.gradle:97 — webBuild 입력에 pnpm-workspace.yaml(webInstall 입력)과 web/.env*가 없다. 설치 결과가 바뀌어도(workspace 설정 변경) 빌드가 UP-TO-DATE로 남고, NEXT_PUBLIC_* 값이 생기면 out에 반영되지 않는다 — pnpm-workspace.yaml을 inputs.files에 더하고, .env 계열을 쓰기 시작하면 함께 넣는다
- [Minor] build.gradle:88 — webInstall 출력이 node_modules 전체라 매 실행마다 수만 파일의 출력 스냅샷을 비교한다(작업 지시 지정) — 느리면 outputs를 node_modules/.pnpm-workspace-state-v1.json 같은 설치 표지 파일 하나로 줄이는 것을 검토한다
- [Minor] build.gradle:82 — pnpm이 PATH에 없으면 "프로세스를 시작하지 못했다"만 나와 원인을 모르겠다. 또 `./gradlew build`/`assemble`도 이제 pnpm을 요구한다 — doFirst에서 pnpm 실행 여부를 확인해 "pnpm이 필요합니다(Node 20.9+)"로 실패시키거나 docs/deploy.md 준비물에 build도 pnpm이 필요하다고 적는다. package.json이 pnpm@12.16.1로 고정이므로 문서에도 그 버전을 적는다
- [Minor] docs/demo-checklist.md:21 — LAN 시연(Caddy 없음)에서는 IDE가 다른 포트의 같은 호스트라 브라우저가 쿠키를 포트와 상관없이 IDE로 보낸다. 쿠키 제거가 없으므로 강사의 JSESSIONID가 학생 컨테이너에 도착할 수 있다 — LAN 시연 절에 "강사 브라우저로 학생 IDE 링크를 열지 않는다(강사 쿠키가 학생 IDE로 간다)"를 한 줄 적는다
- [Minor] deploy/Caddyfile:13 — 쿠키가 전부 지워지면 빈 `Cookie:` 헤더가 upstream에 남는다(보고서의 "빈 값"). code-server는 무시하므로 동작에는 지장이 없다 — 그대로 두거나 `@onlyApp header_regexp` 매처로 헤더 전체를 지우는 별도 줄을 검토한다
- [Minor] docs/specs/2026-11-05-client-adoption-design.md:30 — 설계 문서에 "초대 코드"/"입장 코드"가 남아 있다(30, 61, 67, 71행). 지금 용어 규칙("수업 코드")과 어긋난다 — 문서를 용어에 맞추거나 "(화면 문구는 수업 코드)"라고 한 줄 덧붙인다

## Fix wave 1 결과

위 항목을 줄 순서대로 적는다. 줄 번호와 `/admin` 경로는 옛 값이고, 지금은 `/instructor`다.

### Task 1
- CourseControllerTest 주석 위치: 고침. 주석을 `@MockitoBean provisioner` 바로 위로 되돌렸다.
- HelpResultListenerTest `eq` 완전 한정 이름: 고침. static import로 바꿨다.
- IdeControllerTest `latestHelp.classification`: 고침. 도움 요청이 있는 케이스에서 처리 중 null, 처리 뒤 `CODE`를 확인한다.
- CourseController 목록 N+1: 고침. `SessionRepository.countGroupedByCourse()` 한 번으로 센다. 학생이 없는 수업은 0. 단건 조회·생성은 단순 count 그대로.
- StudentControllerTest statusNeverCarriesPin: 보류. 구현 변경 없이 통과하도록 둔 회귀 방지 테스트라 조치하지 않는다.
- StudentControllerTest import 순서: 고침. `agent.Classification`을 `course` import 앞으로 옮겼다.

### Task 3
- 강사 대시보드 401 조회 중단·안내 (course 화면): 이미 해결됨. Task 4에서 고쳤다(`stopOn`에 401, 데이터가 있어도 401 배너).
- 강사 목록 401 조회 중단·안내: 이미 해결됨. Task 4에서 고쳤다.
- lab 페이지 Suspense fallback: 이미 해결됨. Task 5에서 Spinner를 달았다.
- 강사 수업 화면 Suspense fallback: 고침. Spinner fallback을 달았다.
- 루트 `page.tsx` 빈 화면: 고침. `/join` 링크를 그린다.
- pnpm-lock `@pnpm/exe` 항목: 보류. pnpm 12가 직접 쓴 값이고 의도한 변화다.
- Spring의 확장자 없는 경로·`.txt` 제공: 이미 해결됨. Task 2/8과 e2e에서 확인했다.

### Task 4
- SettingsTab 삭제 뒤 배너: 고침. 삭제 결과를 수업 화면이 쥐고, 못 지운 자원이 있어 머물면 상단 배너가 "삭제된 수업입니다"로 바뀐다. 실패 목록은 설정 탭에 그대로 보인다.
- Python 3.8·3.9: 이미 해결됨. e2e에서 3.8 + requests가 READY까지 갔다.
- "프로젝트"·"수업" 혼용: 고침. 화면 문구를 "수업"으로 통일했다(탭 "수업 설정", 새 수업 화면, 오류 배너, 목록의 "강의" 두 곳 포함). "수업 코드"는 그대로.
- 재개 중 폴링 겹침: 보류. 동작에 문제 없다.

### Task 5
- LocalPanel CodeBlock 복사 버튼: 고침. CodeBlock에는 복사를 대신할 훅이 없어(`onCopy`는 복사 뒤 알림) 보안 컨텍스트가 아니면 "직접 선택해 복사하세요" 안내를 보인다.
- LocalPanel `isSafeHref`: 고침. 둘째 글자로 `\`도 막는다.
- clipboard.ts 포커스: 고침. 대체 경로에서 이전 포커스를 복원한다.
- IdeFrame 복사 상태: 고침. 비밀번호·주소가 바뀌면 idle로 되돌리고, 결과 문구는 항상 있는 `role="status"` 영역에 쓴다(버튼 문구는 "복사" 고정).
- lab 환경 불일치 문구: 고침. MISMATCH일 때 본문 배너에도 문구 목록을 보인다.
- (e2e 발견) 종료된 수업의 코드 zip 버튼 RSC prefetch 404: 고침. zip 버튼과 스크립트 링크를 일반 앵커(`as="a"`)로 바꿨다.

### Task 2
- WebConfigTest 컨트롤러 우선 검증: 고침. 처리기 종류가 `StudentController`임을 확인한다.
- SecurityConfigTest 대소문자 경로: 이미 해결됨. Task 8에서 처리했다.
- SecurityConfigTest HEAD·OPTIONS·중첩 .txt: 이미 해결됨. Task 8에서 처리했다.
- SecurityConfig `/admin/login` 공개와 next 처리: 이미 해결됨. Task 8에서 처리했다.

### Task 8
- AuthResponses `handle()` 문구: 고침. `CsrfException`일 때만 CSRF 문구, 아니면 "권한이 없습니다."(테스트 추가).
- 세션 `timeout: 8h`: 고침(문서). 설정은 그대로 두고 spec 6-1에 "마지막 요청 뒤 8시간(유휴 시간 제한)"이라고 적었다.
- 루프백이 아닌 `X-Forwarded-Proto`: 보류. 다음 배포 때 배포 서버에서 직접 확인한다.

### Task 6
- IdeControllerTest "수업 수업 코드": 고침.
- WebConfigTest 낡은 주석: 고침. main에는 정적 파일이 없다고 적었다.
- build.gradle webBuild 입력: 고침. `pnpm-workspace.yaml`과 `web/.env*`(없어도 되는 fileTree)를 더했다.
- build.gradle webInstall 출력 = node_modules: 보류. 성능 문제일 뿐이다. 빌드가 느려지면 다시 본다.
- pnpm 없음 안내: 고침. webInstall이 PATH에서 pnpm을 찾지 못하면 안내 문구로 실패한다. docs/deploy.md 준비물에 `build`·`bootJar`가 pnpm을 요구하고 12.16.1로 자동 전환(첫 사용 때 네트워크 필요)한다고 적었다.
- demo-checklist LAN 쿠키: 고침. LAN 시연 절에 강사 브라우저로 학생 IDE 링크를 열지 말라고 적었다.
- deploy/Caddyfile 빈 `Cookie:` 헤더: 보류. code-server가 무시하므로 무해하다.
- spec 용어("초대 코드"/"입장 코드"): 고침. spec의 현재 화면 설명을 "수업 코드"로 맞췄다.

## Fix wave 1 re-review
- [Minor] web/src/components/lab/LocalPanel.tsx:Command — 보안 컨텍스트가 아니면 `Command`마다 같은 "직접 선택해 복사하세요" 안내가 붙어(진단·설치 명령 2곳) 같은 문장이 반복된다. 동작에는 지장 없다 — 패널 위쪽에 한 번만 보이거나, `CodeBlock`의 `hasCopyButton={false}`와 `endContent`에 `copyText`를 쓰는 자체 복사 버튼을 두면 http에서도 복사가 된다
- [Minor] web/src/app/instructor/course/page.tsx:CourseView — `deleted` 상태를 위로 올린 뒤 `courseId`가 바뀌어도(클라이언트 이동으로 같은 컴포넌트가 재사용되면) 이전 수업의 삭제 결과가 남는다. 지금 화면 흐름(목록을 거쳐 이동)에서는 컴포넌트가 새로 만들어져 드러나지 않는다 — `<CourseView key={courseId} …>`로 수업마다 상태를 나눈다

## Final review
- [Important] web/src/app/instructor/course/StudentsTab.tsx:85 (ConnectionCell), :70 (EnvCell), :158 (latestReport) — 옛 dashboard.html이 보이던 것 중 새 화면에서 빠진 것이 있다: 최근 에이전트 조치 기록 최대 5건(시각·원인·판단·결과·도구 순서·요약), 기동 시간(`startupMillis` ms), 마지막 확인 시각(`lastSeenAt`), 내 PC 상세(os/arch/python, `local.failed` 목록). 지금은 최근 요약 한 줄과 상태 이름만 나온다. spec 5절("최근 에이전트 기록, 시작 시간")과 목표 1(옛 HTML이 하던 일을 모두 한 뒤 지움)에 어긋나고, demo-checklist 목표 4의 "조치 내역(도구 순서)" 확인을 새 화면으로는 할 수 없다. 환경 불일치·추가 패키지 문구도 Tooltip에만 있다 — 학생 행에 펼침(또는 행 아래 상세)을 두어 `recentActions`를 `시각 · 원인 · 판단 · 결과`, `도구: a → b`, 요약 순으로 텍스트로 그리고, ConnectionCell 아래에 "마지막 확인 HH:MM", "기동 N ms", EnvCell 아래에 local 상세와 failed 목록, issues/extras를 본문 목록으로 보인다
- [Important] docs/deploy.md:130 — "코드를 바꾼 뒤 다시 배포"가 jar 교체와 재시작만 적고 Caddyfile 갱신을 말하지 않는다(팀 서버 재배포 메모도 같다). 이번 변경은 deploy/Caddyfile(/ide/* 에서 JSESSIONID·XSRF-TOKEN 제거)를 바꿨으므로 jar만 올리면 강사 세션 쿠키가 학생 컨테이너로 간다 — "deploy/Caddyfile이 바뀌었으면: 도메인을 바꿔 /etc/caddy/Caddyfile로 복사, `caddy validate`, `sudo systemctl reload caddy`" 줄을 더하고, 배포 서버의 Caddy 버전(`caddy version`)에서 `caddy adapt --config /etc/caddy/Caddyfile`의 reverse_proxy `headers.request.replace.Cookie`에 search_regexp 두 개가 들어가는지 확인한다(쿠키 제거는 로컬 caddy:2 최신판에서만 확인했다)
- [Minor] src/main/java/com/swunion/lab/config/WebConfig.java:36 — 옛 주소(`/join.html?code=`, `/student.html?t=`, `/dashboard.html?course=`, `/instructor.html`)가 배포 뒤 404이거나 새 화면을 엉뚱한 주소로 연다. 이미 나눠 준 링크나 열려 있는 학생 탭이 깨진다 — 쿼리를 유지하는 리다이렉트(`setKeepQueryParams(true)`)로 `/join.html`→`/join`, `/student.html`→`/lab`(t 유지), `/instructor.html`→`/instructor`를 두거나, 배포 공지에 "옛 링크는 /join에서 다시 입장"을 적는다(`dashboard.html?course=`는 `id` 이름이 달라 `/instructor`로만 보낸다)
- [Minor] docs/deploy.md:120 — 확인 절이 학생이 "IDE 열기"를 누른다고 적지만 새 실습 화면은 IDE를 화면 안 iframe으로 띄우고 "새 탭에서 열기"만 있다 — "실습 화면 안에 IDE가 뜨고, IDE 비밀번호를 붙여 넣어 로그인한다"로 고친다
- [Minor] docs/deploy.md:118 (확인 절) — e2e는 localhost http에서만 돌아 IDE가 다른 출처(localhost:<포트>)였다. 배포에서 처음 생기는 조합(같은 출처 /ide/<포트>/ iframe, HTTPS iframe 안의 실습 도우미 webview, Secure 쿠키, Caddy 쿠키 제거)은 확인되지 않았다 — 확인 절에 "학생 실습 화면의 iframe 안에서 로그인 후 실습 도우미 패널이 정상", "강사 로그인 응답의 Set-Cookie에 Secure"를 더하고 배포 직후 그대로 본다
- [Minor] docs/superpowers/plans/2026-11-05-client-adoption.md:38, :69, :158, docs/specs/2026-11-05-client-adoption-design.md:48 — 원격 저장소에 올라갈 문서에 작업 진행 방식 문구와 제목의 작업 수준 괄호, 작성자 표현이 남아 있다 — 커밋 전에 38행을 지우고, 제목의 괄호를 빼고, 작성자 표현을 "(작업 중 정함)"으로 바꾼다
- [Minor] docs/superpowers/plans/2026-11-05-client-adoption.md:29 — Review Focus 2가 "브라우저 로그인 창", "없는 로그인 페이지로 돌리지 않고 새로고침 안내"라고 적어 Task 8 이후 동작(로그인 페이지, 401이면 로그인 화면으로 이동)과 반대다 — Task 8 동작으로 고친다
- [Minor] web/src/lib/types.ts:1 — "API_CONTRACT.md와 같이 고친다" 주석이 지운 파일을 가리킨다 — "서버 응답(Java 뷰)과 같이 고친다"로 바꾼다
- [Minor] src/main/java/com/swunion/lab/config/WebConfig.java:26 — 리소스 핸들러에 캐시 기간이 없어 Spring Security가 모든 응답에 `Cache-Control: no-store`를 붙인다. 해시가 붙은 `/_next/static/**`도 화면을 열 때마다 다시 받는다(학생 30명이 동시에 입장할 때 부담) — `/_next/static/**`만 따로 `setCacheControl(CacheControl.maxAge(365, DAYS).immutable())`로 등록한다(HTML·.txt는 지금처럼 캐시하지 않는다)
- [Minor] (저장소 밖) .superpowers/deploy/smoke.py, e2e_https.py, cleanup.py — 배포 뒤 확인 스크립트가 HTTP Basic과 `/join.html`을 쓴다. 이번 배포 뒤에는 401·404로 실패한다 — `/instructor/login` 세션 로그인(CSRF 쿠키를 받아 X-XSRF-TOKEN 헤더)과 `/join`으로 바꾼 뒤 배포 확인에 쓴다

## Fix wave 2 결과

### Final review
- StudentsTab 정보 누락(Important): 고침. 학생 행을 펼치면 최근 조치 5건(시각·원인·판단·결과, 도구 순서, 요약), 마지막 확인 시각, 기동 시간, 내 PC 상세와 실패 항목, 환경 불일치 문구, 추가 패키지가 텍스트로 나온다.
- deploy.md 재배포에 Caddyfile 단계 없음(Important): 고침. 복사·validate·reload 단계와 `caddy version`, `caddy adapt`에서 Cookie 치환 규칙 두 개를 확인하는 줄을 더했다.
- 옛 주소 404: 고침. WebConfig가 `/join.html`→`/join`, `/student.html`→`/lab`, `/instructor.html`·`/dashboard.html`→`/instructor`로 쿼리를 유지해 보낸다. `/instructor.html`은 보안 규칙에 먼저 걸려 로그인 없이는 로그인 페이지로 간다(테스트 추가).
- deploy.md "IDE 열기" 표현: 고침. 실습 화면 안 iframe에서 IDE 비밀번호를 한 번 붙여 넣는 흐름으로 적었다.
- HTTPS 조합 확인 항목: 고침. 같은 출처 `/ide/<포트>/` iframe, 실습 도우미 패널, Secure 쿠키, `/ide/*` 요청의 쿠키 제거를 확인 절에 더했다.
- 문서의 작업 방식 표시: 고침. 세 문서에서 진행 방식 문구, 제목의 작업 수준 괄호, 작성자 표현을 지웠다.
- Review Focus 2 낡은 설명: 고침. 로그인 페이지와 401 시 `/instructor/login?next=…` 이동으로 고쳤다. 개요의 HTTP Basic 설명도 세션 로그인으로 맞췄다.
- types.ts 주석의 지운 파일 언급: 고침. 서버 Java 뷰를 가리킨다.
- `/_next/static/**` 캐시: 고침. 365일 immutable로 따로 등록했고 Spring Security의 no-store가 덮지 않음을 테스트로 확인했다. HTML·RSC는 계속 no-store다.
- 저장소 밖 확인 스크립트(smoke.py 등): 보류. 저장소 밖 로컬 스크립트, 배포 확인 때 갱신.

### Fix wave 1 re-review
- LocalPanel 복사 안내 반복: 고침. 패널 위에서 한 번만 보인다.
- CourseView `deleted` 상태 잔존: 고침. `key={courseId}`로 수업마다 새로 만든다.
