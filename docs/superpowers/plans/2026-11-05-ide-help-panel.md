# IDE 안 도움 패널 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** code-server 안 "실습 도우미" 사이드바 패널에서 학생이 환경 상태를 보고, 도움을 요청하고, 강사를 호출한다.

**Architecture:** 서버는 세션마다 바뀌지 않는 컨테이너 키(`ideKey`)를 발급해 컨테이너 환경변수로 넣고, 그 키로 인증하는 `/api/ide/*` API를 연다. 기본 이미지(`swunion/lab-base:2`)에 의존성 없는 순수 JS VS Code 확장을 설치하고, 확장의 Node 쪽 코드가 서버를 부르며 webview와는 메시지로만 주고받는다.

**Tech Stack:** 기존 그대로 + VS Code 확장 API(code-server 4.144.0), Node 내장 `http`/`node:test`

**Spec:** `docs/specs/2026-11-05-ide-help-panel-design.md`

## Global Constraints

- 커밋·push는 사용자가 요청할 때만. 계획에 커밋 단계 없음. 현재 `main`에는 수명 관리 기능이 커밋되지 않은 채 있다. 그 변경을 지우거나 되돌리지 않는다.
- `.env`, 토큰, IDE 비밀번호, PIN, 컨테이너 키를 응답·로그·`toString`에 남기지 않는다. `/api/ide/*` 응답에는 IDE 주소·비밀번호·토큰·키를 넣지 않는다.
- R13: `StudentSession`은 담당 컬럼만 바꾸는 업데이트로 쓴다. 새 행 insert만 엔티티 전체 저장.
- 컨테이너 키: 무작위 32바이트, 소문자 16진 64자. 헤더 이름 `X-Session-Key`. 환경변수 `SWUNION_IDE_KEY`, `SWUNION_SERVER_URL`. 설정 `lab.server-url-for-containers`, 기본 `http://host.docker.internal:8080`.
- 기본 이미지 태그 `swunion/lab-base:2`. `swunion/lab-base:1`과 그것으로 만든 예전 수업 이미지는 지우거나 바꾸지 않는다.
- 확장: npm 의존성 없음, 빌드 단계 없음, 외부 리소스 없음. 서버 문자열은 텍스트로만 그린다.
- 실제 수업(수업 3 class1, 수업 6 new class)과 그 컨테이너·볼륨·이미지, `./data/db`는 건드리지 않는다. 통합 테스트는 테스트 전용 접두사와 인메모리 H2.
- 사용자 문구는 한국어, 짧게.

## Review Focus

1. 다른 학생의 키로 요청 → 그 키 주인의 데이터만 나온다. 키 하나로 다른 세션에 닿는 경로가 없다. → Task 1 테스트
2. PIN 되찾기로 토큰이 바뀐 뒤에도 패널이 계속 동작한다(키는 그대로). → Task 1 테스트, Task 3 실제 흐름
3. 컨테이너를 다시 만들 때(`docker rm -f` 뒤 되찾기·재개) 같은 키가 환경변수로 들어간다. → Task 1 테스트
4. 서버가 꺼졌거나 환경변수가 없을 때 → 패널은 "서버에 연결하지 못했습니다"만 보이고 IDE에 오류 팝업이 뜨지 않는다. → Task 2 테스트
5. 에이전트가 처리 중일 때 다시 도움 요청 → 409 문구가 패널에 그대로 보인다. → Task 1 테스트(API), Task 2 테스트(표시)

---

## File Structure

```
session/StudentSession.java, SessionRepository.java, SessionService.java   # Task 1: ideKey 발급
session/ContainerSpecs.java                                                # Task 1: 환경변수 2개
docker/DockerJavaGateway.java                                              # Task 1: host.docker.internal:host-gateway
config/LabProperties.java, application.yml                                 # Task 1: serverUrlForContainers / Task 2: baseImage :2
help/HelpService.java                                                      # Task 1: 세션 기준 메서드
session/StudentStatusView.java (신규)                                      # Task 1: 학생·IDE 공통 상태 응답
ide/IdeController.java (신규)                                              # Task 1: /api/ide/*
dashboard/DashboardService.java                                            # Task 1: 이슈 문구 함수를 공유용으로 꺼냄
docker/lab-base/extension/swunion-helper/ (신규)                           # Task 2: 확장
docker/lab-base/Dockerfile, scripts/build-base-image.sh                    # Task 2
src/test/.../ide/IdePanelIT.java (신규)                                    # Task 3
docs/demo-checklist.md                                                     # Task 3
```

---

### Task 1: 서버 쪽 (컨테이너 키, 환경변수, /api/ide)

**Files:**
- Create: `session/StudentStatusView.java`, `ide/IdeController.java`
- Modify: `session/StudentSession.java`(필드 `ideKey`, unique, nullable), `session/SessionRepository.java`(`findByIdeKey`), `session/SessionService.java`(insert 시 발급), `session/ContainerSpecs.java`, `docker/DockerJavaGateway.java`, `config/LabProperties.java` + `application.yml`(`server-url-for-containers`), `help/HelpService.java`, `session/StudentController.java`(공통 상태 뷰 사용), `dashboard/DashboardService.java`
- Test: `ide/IdeControllerTest.java`, `session/ContainerSpecsTest.java`, 기존 테스트 갱신

**Interfaces:**
- Produces:
  - `StudentSession.getIdeKey()`; 새 세션은 insert 때 키를 받는다(기존 행은 null).
  - `SessionService.byIdeKey(String key) -> StudentSession` (없거나 빈 값 → `SessionNotFoundException`)
  - `HelpService.request(StudentSession s, String errorText) -> HelpRequest`, `HelpService.escalate(StudentSession s)` (기존 토큰 메서드는 이것을 부른다)
  - `StudentStatusView.of(StudentSession s, ...) -> Map<String,Object>` 키: `name, courseName, envStatus, issues, issuesText, needsHelp, latestHelp, workspaceLost` (`issuesText`는 대시보드와 같은 한국어 문구 목록). 학생 API는 여기에 `ideUrl, idePassword`를 더한다.
  - `IssueTexts.of(List<Issue>) -> List<String>` (대시보드에서 꺼낸 공유 함수, 위치는 `check` 패키지)
  - API: `GET /api/ide/status` 200, `POST /api/ide/help` `{errorText}` 202 `{helpId}` / 409 `{error}`, `POST /api/ide/escalate` 204. 키 없음·틀림 401 `{error:"인증 정보가 없습니다"}`. 수업이 READY가 아니면 help·escalate는 학생 API와 같은 오류.
  - `ContainerSpecs.forSession`: 키가 있으면 env에 `SWUNION_IDE_KEY`, 항상 `SWUNION_SERVER_URL` 추가(PASSWORD 유지).
  - `DockerJavaGateway.createAndStart`: 모든 컨테이너에 extra host `host.docker.internal:host-gateway`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
// ContainerSpecsTest
withKey:        ideKey 있는 세션 → env = {PASSWORD, SWUNION_IDE_KEY=<key>, SWUNION_SERVER_URL=<설정값>}
legacyNoKey:    ideKey null → SWUNION_IDE_KEY 없음, SWUNION_SERVER_URL은 있음
sameKeyOnRecreate: ContainerEnsurer 재생성 경로의 createAndStart 스펙에도 같은 키               (Review Focus 3)
specToStringMasks: ContainerSpec.toString에 키 값 없음

// SessionJoinPinTest 추가
newSessionGetsIdeKey:   새 입장 → ideKey 64자 [0-9a-f]
rejoinKeepsIdeKey:      PIN 되찾기로 토큰 교체 → ideKey 그대로                                    (Review Focus 2)

// IdeControllerTest (MockMvc)
statusWithKey:          200, 필드 9개, ideUrl·idePassword·token 없음, issuesText가 대시보드 문구와 같음
noKey / wrongKey:       401 {error}
otherSessionKey:        학생 B 키 → 학생 B 이름과 상태만                                         (Review Focus 1)
helpAccepted:           202 {helpId}; 처리 중이면 409 {error}                                   (Review Focus 5)
escalate:               204, needsHelpReason에 "학생 호출" 덧붙음
endedCourse:            help·escalate가 학생 API와 같은 오류
keyNotLogged:           요청 처리 중 로그 출력에 키 문자열 없음
```

- [ ] **Step 2:** `./gradlew test --tests '*ContainerSpecsTest' --tests '*IdeControllerTest' --tests '*SessionJoinPinTest'` → FAIL
- [ ] **Step 3:** 구현. 키 조회는 DB 동등 비교로 한다(256비트 무작위 값이라 타이밍 공격은 의미 없음). 이슈 문구 함수는 대시보드에서 꺼내 대시보드도 그것을 쓴다(중복 금지).
- [ ] **Step 4:** `./gradlew test` → PASS

---

### Task 2: 확장과 기본 이미지 :2

**Files:**
- Create: `docker/lab-base/extension/swunion-helper/package.json`, `extension.js`, `lib/client.js`, `lib/view-model.js`, `media/icon.svg`, `test/client.test.js`, `test/view-model.test.js`
- Modify: `docker/lab-base/Dockerfile`, `scripts/build-base-image.sh`(태그 `:2`), `config/LabProperties` 기본 `baseImage` + `application.yml`(`swunion/lab-base:2`), 기본값을 단언하는 기존 테스트

**Interfaces:**
- Consumes: Task 1 API와 응답 필드
- Produces (확장 내부):
  - `lib/client.js`: `createClient({serverUrl, key, httpModule})` → `{status(), help(errorText), escalate()}`; 각 Promise는 `{ok:true, data}` 또는 `{ok:false, message}`(사용자 문구)로 끝나고 절대 reject하지 않는다. 시간 제한 10초.
  - `lib/view-model.js`: `toView(statusJson) -> {envLabel, envKind:'ok'|'mismatch'|'repairing'|'unknown', issues:[string], help:{statusLabel, explanation, escalated}|null}`
  - 명령 ID `swunionHelper.sendSelection`, 뷰 ID `swunionHelper.panel`, 활동 막대 컨테이너 ID `swunionHelper`

- [ ] **Step 1: 실패하는 테스트 작성** (`node --test`, 가짜 http 모듈)

```js
statusOk:            200 JSON → {ok:true, data}; 요청 헤더 X-Session-Key = key
serverDown:          ECONNREFUSED → {ok:false, message:"서버에 연결하지 못했습니다"}, reject 없음     (Review Focus 4)
noEnv:               serverUrl 또는 key 없음 → 요청 없이 {ok:false, message:"이 수업에서는 IDE 도움 패널을 쓸 수 없습니다. 학생 화면을 이용하세요"}
timeout:             10초 무응답 → {ok:false, message:"서버에 연결하지 못했습니다"}
help409:             409 {error:"..."} → {ok:false, message:그 error 문구}                        (Review Focus 5)
viewRepairing:       envStatus REPAIRING → envKind 'repairing', envLabel "복구 중"
viewMismatch:        MISMATCH + issuesText 2개 → issues 2개 그대로
viewEscalatedNoExplanation: latestHelp ESCALATED, explanation null → explanation "강사에게 전달했습니다"
```

- [ ] **Step 2:** `node --test docker/lab-base/extension/swunion-helper/test/` → FAIL
- [ ] **Step 3:** 구현. webview는 CSP(`default-src 'none'`, nonce가 붙은 스크립트·스타일만)를 걸고 VS Code 테마 변수로 색을 맞춘다. 패널이 보일 때만 5초마다 상태를 읽고 요청이 겹치지 않게 한다. `sendSelection`: 편집기는 선택 영역, 터미널은 `workbench.action.terminal.copySelection` 뒤 클립보드를 읽어 입력칸에 넣고 패널을 연다(바로 보내지 않음). 편집기 우클릭(`editorHasSelection`일 때)과 터미널 우클릭 메뉴에 등록.
- [ ] **Step 4:** `node --test ...` → PASS
- [ ] **Step 5:** Dockerfile에서 확장을 code-server에 설치(vsix로 묶어 `--install-extension`하거나 확장 폴더에 직접 배치, code-server 4.144.0에서 실제 로드 확인). 태그 `:2`로 빌드.
- [ ] **Step 6: 검증** `scripts/build-base-image.sh` 후 `docker run --rm --entrypoint code-server swunion/lab-base:2 --list-extensions`에 `swunion.swunion-helper`와 `ms-python.python`이 있음. `./gradlew test` → PASS(기본값 테스트 갱신 포함).

---

### Task 3: 실제 Docker 통합 테스트, 실제 흐름, 문서

**Files:**
- Create: `src/test/java/com/swunion/lab/ide/IdePanelIT.java` (`integration`)
- Modify: `docs/demo-checklist.md` ("IDE 도움 패널" 절)

- [ ] **Step 1: 통합 테스트** (테스트 전용 접두사, 인메모리 H2, 서버를 정해진 포트로 띄우고 `lab.server-url-for-containers=http://host.docker.internal:{포트}`)

```java
containerReachesServerWithKey: six 수업(기본 이미지 :2) → 학생 입장 → docker exec -u coder: curl -s -o /dev/null -w '%{http_code}' -H "X-Session-Key: $SWUNION_IDE_KEY" "$SWUNION_SERVER_URL/api/ide/status" → 200
extensionInstalled:            같은 컨테이너에서 code-server --list-extensions에 swunion.swunion-helper
keySurvivesRecreate:           docker rm -f 뒤 같은 이름·PIN으로 되찾기 → 새 컨테이너의 SWUNION_IDE_KEY가 이전과 같음
```

- [ ] **Step 2:** `./gradlew integrationTest --tests '*IdePanelIT'` → PASS, 이어서 `./gradlew test integrationTest` 전체 PASS
- [ ] **Step 3: 실제 흐름** 서버를 띄우고(실제 DB, 점검 켬 여부는 보고에 적음) 새 시연 수업을 만든 뒤 헤드리스 브라우저로 IDE에 로그인해 "실습 도우미" 패널을 연다. 환경 상태 "정상" 확인 → 학생 터미널에서 패키지 삭제 → 패널에 불일치나 복구 중 표시 → 편집기 텍스트 선택 후 명령으로 입력칸 채움 → 도움 요청 결과 표시 → 강사 호출이 대시보드에 반영. 끝나면 시연 수업을 삭제 API로 지우고 서버 종료.
- [ ] **Step 4:** `docs/demo-checklist.md`에 "IDE 도움 패널" 절 추가(자동 테스트 / 빌드·실행 / 실제 흐름, 시연 순서, 알려진 한계). 기본 이미지를 `:2`로 다시 빌드해야 한다는 사전 준비 문구 포함.
