# 수업·세션 수명 관리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학생이 입장 코드 + 이름 + PIN으로 원래 세션에 돌아오고, 강사가 끝난 수업을 재개·삭제하며 학생 한 명을 재시작·삭제·PIN 초기화할 수 있게 한다.

**Architecture:** 컨테이너를 "돌게 만드는" 로직을 `ContainerEnsurer` 하나로 모아 되찾기·재개·재시작이 함께 쓴다. 입장은 `SessionService.join`에 PIN 분기를 더하고, 강사용 수명 관리는 새 `lifecycle` 패키지(서비스 + 컨트롤러 + 행 삭제 컴포넌트)에 둔다. 수업 단위 작업(종료·재개·삭제)은 `CourseLocks`로 수업마다 하나씩만 돈다.

**Tech Stack:** 기존 그대로 (Spring Boot 4.1.2, Java 21 대상, JPA + H2, docker-java 3.5.1, Spring Security의 `BCryptPasswordEncoder`, 정적 HTML + 바닐라 JS)

**Spec:** `docs/specs/2026-11-05-session-lifecycle-design.md` (기준 문서 `docs/specs/2026-11-04-prototype-design.md`)

## Global Constraints

- 저장소: `/Users/gogror0987/swunion` (git, main). **커밋·push는 사용자가 요청할 때만** 한다. 계획에 커밋 단계를 넣지 않는다.
- `.env`는 읽거나 출력하지 않는다. 토큰, IDE 비밀번호, PIN, PIN 해시를 응답·로그·`toString`에 남기지 않는다.
- R13: `StudentSession`은 담당 컬럼만 바꾸는 `@Modifying` JPQL 업데이트로 쓴다. 엔티티 전체 `save`는 새 행을 만들 때만 허용한다.
- PIN: 숫자 정확히 4자리(`^[0-9]{4}$`). BCrypt 해시로 저장. 연속 5회 실패 → 5분 잠금. 성공하면 실패 횟수 0.
- 이름 비교 키: 앞뒤 공백 제거 + `toLowerCase(Locale.ROOT)`.
- 강사 API는 `/api/courses/**` 아래(HTTP Basic). 학생 API는 토큰 경로 유지.
- 지울 Docker 자원은 DB에 기록된 컨테이너 ID, 볼륨 이름, 이미지 이름으로만 정한다. 접두사 훑기 삭제 금지.
- 화면은 서버 문자열을 `textContent`로만 그린다. `alert`/`confirm` 금지(두 번 누르기 방식).
- 테스트 태그: 단위 = 태그 없음(`./gradlew test`), `integration` = Docker 필요(`./gradlew integrationTest`). 통합 테스트는 테스트 전용 `lab.course-image-prefix`·`lab.container-name-prefix`와 인메모리 H2를 쓰고, 만든 자원을 finally에서 지운다.
- 사용자 문구는 한국어, 짧게.

## Review Focus

1. 같은 새 이름으로 두 학생이 동시에 입장 → 세션 행은 하나만 생기고, 나중 요청은 PIN 확인 흐름을 탄다. → Task 3 테스트
2. `"Kim "`과 `"kim"`처럼 공백·대소문자만 다른 이름 → 같은 학생으로 본다. → Task 3 테스트
3. 컨테이너·볼륨을 누가 이미 손으로 지운 수업을 삭제 → 없는 자원은 성공으로 보고, DB 행은 모두 지운다. → Task 4 테스트
4. 다른 PC에서 되찾은 뒤 예전 탭의 학생 화면 → 깨진 화면이 아니라 "입장 화면에서 이름과 PIN으로 다시 들어오세요" 안내. → Task 5 검증
5. Docker 재시작 등으로 호스트 포트가 바뀐 뒤 재개·되찾기 → 포트를 다시 읽어 IDE 링크가 맞다. → Task 2 테스트

---

## File Structure

```
docker/DockerGateway.java, DockerJavaGateway.java      # Task 1: containerExists, volumeExists, removeImage, 없는 자원 삭제는 성공
session/ContainerSpecs.java                             # Task 2: 세션 → ContainerSpec (join과 재생성이 공유)
session/ContainerEnsurer.java                           # Task 2: 컨테이너가 돌게 만들기
session/StudentSession.java, SessionRepository.java     # Task 2, 3: 새 컬럼과 대상 업데이트
session/SessionService.java, StudentController.java     # Task 3: PIN 입장/되찾기
session/PinPolicy.java                                  # Task 3: 형식 검사, 해시, 잠금 규칙
course/CourseLocks.java                                 # Task 4: 수업별 잠금
course/CourseService.java                               # Task 4: end가 CourseLocks 사용
lifecycle/LifecycleService.java, LifecycleController.java, LifecycleRows.java   # Task 4
agent/AgentActionRepository.java, help/HelpRequestRepository.java,
check/EnvironmentCheckRepository.java, local/LocalInstallRepository.java        # Task 4: 삭제 쿼리
dashboard/DashboardView.java, DashboardService.java     # Task 5: pinSet
static/join.html, student.html, dashboard.html          # Task 5
src/test/.../lifecycle/LifecycleIT.java                 # Task 6
docs/demo-checklist.md                                  # Task 6
```

---

### Task 1: DockerGateway 보강

**Files:**
- Modify: `src/main/java/com/swunion/lab/docker/DockerGateway.java`, `DockerJavaGateway.java`
- Test: `src/test/java/com/swunion/lab/docker/DockerJavaGatewayIT.java` (`integration`)

**Interfaces:**
- Produces:
  - `boolean containerExists(String containerId)` (멈춘 컨테이너도 true, 없으면 false, 예외 없음)
  - `boolean volumeExists(String volumeName)`
  - `void removeImage(String image)` (강제 삭제)
  - 기존 `remove`, `removeVolume`과 새 `removeImage`는 대상이 이미 없으면 예외 없이 끝난다.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
@Test void existenceChecksAndIdempotentRemoval() {
  // lab-base로 컨테이너+볼륨 생성 → containerExists true, volumeExists true
  // gw.stop(id) → containerExists 여전히 true, isRunning false
  // gw.remove(id); gw.remove(id)  → 두 번째도 예외 없음, containerExists false
  // gw.removeVolume(v); gw.removeVolume(v) → 예외 없음, volumeExists false
  // 임시 태그 이미지 빌드 → removeImage(tag) 두 번 → 예외 없음, imageExists false
}
```

- [ ] **Step 2:** `./gradlew integrationTest --tests '*DockerJavaGatewayIT'` → FAIL
- [ ] **Step 3:** 구현. 없는 자원(docker-java `NotFoundException`)만 성공으로 삼키고, 다른 오류는 `DockerOperationException`으로 던진다.
- [ ] **Step 4:** 같은 명령 → PASS, `./gradlew test` → PASS

---

### Task 2: ContainerEnsurer

**Files:**
- Create: `session/ContainerSpecs.java`, `session/ContainerEnsurer.java`
- Modify: `session/StudentSession.java`(필드 `workspaceLostAt: Instant`), `session/SessionRepository.java`, `session/SessionService.java`(join이 `ContainerSpecs` 사용, `restartContainer`가 READY 확인 뒤 `ContainerEnsurer` 사용), `session/StudentController.java`(`GET /api/s/{token}`에 `workspaceLost: boolean`)
- Test: `session/ContainerEnsurerTest.java`

**Interfaces:**
- Consumes: Task 1의 `containerExists`, `volumeExists`
- Produces:
  - `ContainerSpecs.forSession(StudentSession s, Course c) -> ContainerSpec` (이름·볼륨 = `s.getVolumeName()`, 라벨·제한은 지금 join과 동일)
  - `enum EnsureOutcome { ALREADY_RUNNING, STARTED, RECREATED, RECREATED_EMPTY, FAILED }`
  - `record EnsureResult(EnsureOutcome outcome, Integer hostPort)`
  - `ContainerEnsurer.ensureRunning(StudentSession s) -> EnsureResult` (수업 상태를 보지 않는다. 호출자가 정한다. health는 기다리지 않는다.)
  - `SessionRepository.markWorkspaceLost(long id, Instant at)`

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 게이트웨이)

```java
running:          isRunning=true → ALREADY_RUNNING, 게이트웨이 변경 호출 0회
stopped:          containerExists=true, isRunning=false → restart 1회, 새 포트 저장(updateContainerState), STARTED
portChanged:      restart 뒤 hostPort가 41000→41007 → 세션 hostPort 41007         (Review Focus 5)
missingContainer: containerExists=false, volumeExists=true → createAndStart(spec.volumeName == s.getVolumeName()), updateContainerCreated, RECREATED
missingBoth:      containerExists=false, volumeExists=false → 새로 만들고 RECREATED_EMPTY, workspaceLostAt 설정, needsHelp true + 사유 "작업 공간 유실"
failure:          restart가 DockerOperationException → FAILED, 예외 전파 없음
```

- [ ] **Step 2:** `./gradlew test --tests '*ContainerEnsurerTest'` → FAIL
- [ ] **Step 3:** 구현. 사유는 기존 `appendNeedsHelpReason`으로 덧붙인다. join의 spec 생성 코드를 `ContainerSpecs`로 옮겨 중복을 없앤다.
- [ ] **Step 4:** `./gradlew test` → PASS (기존 SessionServiceTest 포함)

---

### Task 3: PIN 입장과 되찾기

**Files:**
- Create: `session/PinPolicy.java`
- Modify: `session/StudentSession.java`(필드 `nameKey`, `pinHash`, `failedPinAttempts:int`, `pinLockedUntil:Instant`; `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"course_id","name_key"}))`), `session/SessionRepository.java`, `session/SessionService.java`, `session/StudentController.java`, `session/JoinException.java`(필요하면 상태 코드 403/429)
- Test: `session/SessionJoinPinTest.java`, `session/StudentControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `ContainerEnsurer.ensureRunning`
- Produces:
  - `record JoinResult(StudentSession session, boolean resumed)`
  - `SessionService.join(String entryCode, String displayName, String pin) -> JoinResult`
  - `SessionService.resetPin(long sessionId)` (pinHash·실패 횟수·잠금 비움)
  - `PinPolicy`: `validate(String pin)`, `hash(String pin)`, `matches(String pin, String hash)`, 상수 `MAX_FAILURES = 5`, `LOCK = Duration.ofMinutes(5)`, 시간은 주입한 `Clock`으로 계산
  - `POST /api/join` 본문 `{entryCode, name, pin}` → 새 세션 201 `{token, resumed:false}`, 되찾기 200 `{token, resumed:true}`, PIN 틀림 403 `{error}`(남은 횟수 포함), 잠김 429 `{error}`(남은 분 포함), 형식 오류 400

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 게이트웨이, 고정 `Clock`)

```java
newNameCreatesWithHashedPin:   join("ABC123","김철수","1234") → resumed false, pinHash != "1234", PinPolicy.matches("1234", pinHash)
rightPinResumesAndRotatesToken: 같은 이름+1234 → resumed true, 같은 세션 id, 새 토큰 ≠ 옛 토큰, byToken(옛 토큰) → SessionNotFoundException
nameKeyIgnoresCaseAndSpaces:   "Kim" 생성 → " kim " + 같은 PIN → 같은 세션                      (Review Focus 2)
wrongPinCountsDown:            틀린 PIN → JoinException 403, 메시지에 "4"(남은 횟수)
fiveFailuresLock:              5회 틀림 → 6번째는 맞는 PIN이어도 429; Clock +5분 → 맞는 PIN 200, 실패 횟수 0
resetPinLetsNextJoinSetPin:    resetPin → 다른 PIN "9999"로 join → resumed true, 이후 "1234"는 403
legacyNoPinAdoptsPin:          pinHash null, nameKey null인 예전 행 → 그 이름으로 join → 그 행으로 resumed, PIN 저장
legacyDuplicatesPickNewest:    같은 이름 예전 행 2개(id 3, 7) → id 7로 resumed
concurrentSameNewName:         같은 새 이름으로 두 스레드 동시 join → 세션 행 1개, 다른 한쪽은 resumed(같은 PIN) 또는 403(다른 PIN)   (Review Focus 1)
invalidPin:                    "12a4", "123", null → 400
resumeEnsuresContainer:        되찾기 시 ensureRunning 1회 호출
endedCourseRejected:           수업 ENDED → 기존과 같은 거절
```

- [ ] **Step 2:** `./gradlew test --tests '*SessionJoinPinTest'` → FAIL
- [ ] **Step 3:** 구현. 같은 이름 찾기는 수업의 세션을 읽어 이름 키로 비교하고, 여럿이면 id가 가장 큰 행. 새 이름 생성은 수업별·이름 키별 잠금 안에서 하고, DB 고유 제약 위반은 "이미 있는 이름"으로 바꿔 PIN 흐름을 탄다. 실패 횟수 증가는 JPQL `+ 1`로 원자적으로. 새 업데이트: `setPin(id, hash)`(실패 0·잠금 null 포함), `recordPinFailure(id, lockedUntilOrNull)`, `resetPin(id)`, `rotateToken(id, token)`, `setNameKey(id, key)`.
- [ ] **Step 4:** `StudentController`: 응답 상태 201/200 분기, 403/429 `{error}`. `./gradlew test` → PASS

---

### Task 4: 수업·학생 수명 관리 API

**Files:**
- Create: `course/CourseLocks.java`, `lifecycle/LifecycleService.java`, `lifecycle/LifecycleRows.java`, `lifecycle/LifecycleController.java`
- Modify: `course/CourseService.java`(`end`가 `CourseLocks` 안에서 실행), 4개 저장소에 삭제 쿼리, `config/LabProperties`는 바꾸지 않음
- Test: `lifecycle/LifecycleServiceTest.java`, `lifecycle/LifecycleControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `remove`/`removeVolume`/`removeImage`, Task 2 `ensureRunning`, Task 3 `resetPin`, `AgentTrigger.isRunning(long)`, `HealthWaiter`
- Produces:
  - `CourseLocks.run(long courseId, Supplier<T>) -> T` (같은 수업의 종료·재개·삭제·학생 삭제를 직렬화)
  - `record ResumeResult(int started, int unhealthy, int recreatedEmpty, int failed)`
  - `record DeleteResult(int removedContainers, int removedVolumes, List<String> failures)`
  - `LifecycleService.resume(long courseId) -> ResumeResult`
  - `LifecycleService.deleteCourse(long courseId, String confirmName) -> DeleteResult`
  - `LifecycleService.restartStudent(long courseId, long sessionId) -> boolean`
  - `LifecycleService.deleteStudent(long courseId, long sessionId) -> DeleteResult`
  - `LifecycleService.resetPin(long courseId, long sessionId)`
  - `LifecycleRows.deleteSessionRows(long sessionId)`, `deleteCourseRows(long courseId)` (`@Transactional`, 외래 키 순서: 에이전트 조치 → 도움 요청 → 점검 기록 → 로컬 설치 → 세션 → 수업)
  - API (강사 인증): `POST /api/courses/{id}/resume` 200, `DELETE /api/courses/{id}` 본문 `{confirmName}` 200, `POST /api/courses/{id}/sessions/{sid}/restart` 200 `{ok}`, `DELETE /api/courses/{id}/sessions/{sid}` 200, `POST /api/courses/{id}/sessions/{sid}/reset-pin` 204. 상태 오류 409, 확인 이름 불일치 400, 없는 수업·소속 아닌 세션 404, 모두 `{error}`.

- [ ] **Step 1: 실패하는 테스트 작성** (가짜 게이트웨이·가짜 HealthWaiter, 실제 JPA)

```java
resumeOnlyFromEnded:          READY·FAILED → 409
resumeStartsThenReady:        ENDED, 세션 3개(running 아님) → ensureRunning 3회, 그동안 상태 ENDED 유지, 끝나면 READY, ResumeResult(3,0,0,0)
resumeCountsOutcomes:         RECREATED_EMPTY 1, FAILED 1, health 실패 1 → (1,1,1,1) 형태로 집계, 실패가 있어도 READY
deleteNeedsExactName:         confirmName "수업1 " → 400, 아무것도 안 지움
deleteOnlyEndedOrFailed:      READY → 409
deleteBlockedByAgent:         세션 하나 isRunning → 409, 아무것도 안 지움
deleteRemovesOnlyRecorded:    다른 수업 세션의 컨테이너·볼륨, 이 수업 이미지 외 이미지는 게이트웨이 호출 목록에 없음; 이 수업 것만 정확히 호출
deleteToleratesMissing:       게이트웨이가 이미 없는 자원에 정상 반환 → DeleteResult.failures 비어 있음, 수업·세션·하위 행 0개, 작업 폴더 삭제   (Review Focus 3)
deleteReportsOtherFailures:   removeVolume이 DockerOperationException → failures에 볼륨 이름, DB 행은 지워짐
restartStudentRules:          다른 수업 세션 404, 수업 ENDED 409, 에이전트 실행 중 409, 정상 → ensureRunning + health → {ok:true}
deleteStudentRemovesOne:      그 세션의 컨테이너·볼륨·하위 행만 지움, 같은 수업 다른 학생은 그대로
resetPinEndpoint:             204, pinHash null
endUsesCourseLock:            end와 resume을 동시에 → 직렬 실행(겹치지 않음)
controllerAuth:               새 API 5개 모두 인증 없으면 401
```

- [ ] **Step 2:** `./gradlew test --tests 'com.swunion.lab.lifecycle.*'` → FAIL
- [ ] **Step 3:** 구현. 재개는 수업이 ENDED인 동안 세션별 `ensureRunning` + health 대기를 고정 크기 풀에서 병렬로 하고 전체 60초 상한을 둔 뒤 `updateStatusIf(ENDED→READY)`. 삭제는 수업 작업 폴더 `{dataDir}/courses/{id}`도 지운다(경로가 그 폴더 밖이면 거부).
- [ ] **Step 4:** `./gradlew test` → PASS

---

### Task 5: 화면

**Files:**
- Modify: `static/join.html`, `static/student.html`, `static/dashboard.html`, `dashboard/DashboardView.java`(`StudentRow.pinSet: boolean`), `dashboard/DashboardService.java`
- Test: `dashboard/DashboardServiceTest.java`(pinSet), 페이지는 아래 검증 단계

**Interfaces:**
- Consumes: Task 3 `/api/join` 응답, Task 4 API, Task 2 `workspaceLost`

- [ ] **Step 1:** `DashboardServiceTest`에 `pinSetReflectsHash`(pinHash 있음 → true, 초기화 뒤 → false) 추가 → FAIL → 구현 → PASS
- [ ] **Step 2:** `join.html`: PIN 칸(`inputmode="numeric"`, 4자리), 안내 문구 "처음 입장하면 PIN을 새로 정하고, 다시 들어올 때는 처음 정한 PIN을 입력하세요.", 403/429 `{error}` 표시.
- [ ] **Step 3:** `student.html`: 위쪽 안내 "다시 들어올 때는 입장 코드, 이름, PIN이 필요합니다". `workspaceLost`면 "이전 작업 공간을 찾지 못해 새로 만들었습니다". 404를 받으면 "이 화면은 더 이상 쓸 수 없습니다. 입장 화면에서 이름과 PIN으로 다시 들어오세요"와 `join.html` 링크 (Review Focus 4).
- [ ] **Step 4:** `dashboard.html`: 학생 행에 "재시작", "PIN 초기화", "삭제"(두 번 누르기) 버튼과 PIN 미설정 표시. ENDED 수업에 "수업 재개", ENDED·FAILED 수업에 수업 이름 입력칸 + "수업 삭제"(이름이 정확히 같을 때만 켜짐). 결과 숫자 표시, 삭제 뒤 `instructor.html`로 이동.
- [ ] **Step 5: 검증** `./gradlew test` PASS. 각 페이지 인라인 스크립트 `node --check` 통과, `innerHTML|alert|confirm` 검색 결과 없음. 서버를 띄워 curl로 새 API 401(인증 없음)·정상 응답 확인 후 서버 종료.

---

### Task 6: 실제 Docker 통합 테스트와 시연 확인

**Files:**
- Create: `src/test/java/com/swunion/lab/lifecycle/LifecycleIT.java` (`integration`)
- Modify: `docs/demo-checklist.md` ("수명 관리" 절 추가)

- [ ] **Step 1: 통합 테스트 작성** (테스트 전용 접두사, 인메모리 H2, finally 정리)

```java
resumeKeepsFiles:      six 수업, 학생 입장(PIN 1234) → exec로 /home/coder/project/keep.txt 작성 → end → resume → keep.txt 내용 그대로, 수업 READY
rejoinRecreatesWithSameVolume: docker rm -f 그 컨테이너 → join(같은 코드, 이름, 1234) → resumed, 새 컨테이너에 keep.txt 있음
deleteCourseLeavesNothing: end → deleteCourse(정확한 이름) → 기록된 컨테이너·볼륨·이미지 모두 없음(containerExists/volumeExists/imageExists false)
```

- [ ] **Step 2:** `./gradlew integrationTest --tests '*LifecycleIT'` → PASS, 이어서 `./gradlew test integrationTest` 전체 PASS
- [ ] **Step 3: 실제 흐름** 서버를 띄우고 헤드리스 브라우저 두 컨텍스트로: 학생 입장(PIN) → 탭 닫기 → 다른 컨텍스트에서 이름+PIN으로 되찾기 → 같은 IDE 비밀번호로 로그인 → 예전 탭 새로고침 시 안내 문구. 강사 화면에서 PIN 초기화 → 새 PIN으로 되찾기, 수업 종료 → 재개 → 삭제. 서버 종료.
- [ ] **Step 4:** `docs/demo-checklist.md`에 "수명 관리" 절(되찾기, PIN 초기화, 재개, 삭제, 학생 재시작·삭제)을 실행 결과와 함께 추가. 자동 테스트 / 빌드·실행 / 실제 흐름을 나눠 적는다.
