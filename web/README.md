# 실습 지원 서비스 클라이언트

Next.js 16, React 19, [Astryx](https://astryx.atmeta.com/components)(Neutral 라이트). 서버는 `../prototype-server`입니다.

## 실행

```bash
corepack enable   # pnpm이 없을 때 한 번
pnpm install
pnpm dev:mock     # Spring 없이 전부 목업. Docker 없이 화면만 볼 때
```

http://localhost:3000 이 학생 입장 화면, http://localhost:3000/admin 이 강사 화면입니다. 강사 계정 기본값은 `instructor` / `instructor`입니다.

### 실제 서버에 연결

Docker Desktop, uv, JDK 21 이상이 필요하고, 기본 이미지를 한 번 빌드해 둬야 합니다. 터미널 두 개에서 실행합니다.

```bash
cd ../prototype-server
./scripts/build-base-image.sh   # 처음 한 번
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew bootRun
```

```bash
pnpm dev          # Spring(:8080)에 연결. 서버에 없는 API만 목업이 채움
```

- `/var/run/docker.sock`이 없는 Mac에서는 위처럼 `DOCKER_HOST`를 줘야 합니다.
- `prototype-server/.env`에 `ANTHROPIC_API_KEY`가 없으면 도움 요청과 복구가 전부 "LLM 응답 실패"로 강사에게 넘어갑니다.

npm 대신 pnpm을 쓰는 이유: `@astryxdesign/core`는 전체 메타데이터가 65MB라 `npm install`이 받다가 멈춥니다. pnpm은 `pnpm-workspace.yaml`의 `minimumReleaseAge: 0` 덕분에 요약본만 받습니다.

## 화면

| 경로 | 화면 |
| --- | --- |
| `/join` | 학생 입장. `?code=`로 초대 코드를 미리 채울 수 있음 |
| `/lab/{token}` | 학생 실습(IDE), 도움 요청 패널, 내 PC로 이어가기 패널 |
| `/admin/login` | 강사 로그인 |
| `/admin` | 강사 홈 |
| `/admin/new` | 프로젝트 생성 3단계 |
| `/admin/courses/{id}/students` | 학생 목록 |
| `/admin/courses/{id}/settings` | 프로젝트 설정 |

## 목업

서버에 아직 없는 API는 `mock/server.mjs`가 대신합니다. 무엇이 진짜고 무엇이 가짜인지, 서버가 무엇을 구현해야 하는지는 [API_CONTRACT.md](API_CONTRACT.md)에 있습니다.

전체 목업에서 `numpy==3.0.0`처럼 없는 버전을 넣으면 환경 발급 실패 화면을 볼 수 있습니다. `MOCK_EMPTY=1 pnpm dev:mock`은 강의 없는 빈 화면으로 시작합니다.
