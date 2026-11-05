# 서버 배포

Ubuntu 24.04 서버 한 대에 실습 서버를 올리는 방법이다. 설정 파일 원본은 `deploy/` 폴더에 있다. 이 문서의 명령은 `ubuntu` 사용자 기준이다.

## 구성

```
학생·강사 브라우저 ──HTTPS──> Caddy (80, 443)
                               ├─ /ide/<포트>/...  → 127.0.0.1:<포트>  학생 IDE (code-server 컨테이너)
                               └─ 나머지           → 127.0.0.1:8080    실습 서버 (Spring Boot)
학생 컨테이너 ── host.docker.internal:8080 ──> 실습 서버 (IDE 안 "실습 도우미" 패널)
```

- 바깥에서 들어오는 길은 Caddy(443) 하나다. 실습 서버 8080과 학생 IDE 포트는 서버 안에서만 열린다.
- HTTPS가 꼭 필요하다. 브라우저는 localhost가 아닌 http 주소를 안전한 연결로 보지 않고, 그러면 VS Code가 webview를 띄우지 않는다. 실습 도우미 패널이 흰 화면으로 나오고, 터미널 우클릭 복사도 막힌다.

## 준비

- 서버에 SSH로 접속할 수 있다. 보안 그룹에서 80, 443이 열려 있다.
- 도메인이 있다. 없으면 [DuckDNS](https://www.duckdns.org)에서 서브도메인을 만들고 서버 공인 IP를 넣는다. 아래에서는 `your-name.duckdns.org`로 쓴다.
- 로컬에 JDK 21 이상, Node 20.9 이상, pnpm, 이 저장소가 있다. 화면(`web/`)은 로컬에서 jar를 만들 때 함께 빌드된다. 서버에는 Node가 필요 없다.
- 프로젝트 루트 `.env`에 `ANTHROPIC_API_KEY=<키>`가 있다.

## 1. 서버 패키지

```sh
sudo apt-get update
sudo apt-get install -y docker.io openjdk-21-jre-headless iptables caddy
sudo usermod -aG docker ubuntu
curl -LsSf https://astral.sh/uv/0.11.29/install.sh | sh
```

서버는 수업을 만들 때 `uv lock`을 직접 돌린다. uv는 `~/.local/bin/uv`에 설치되고, 서비스 설정의 `PATH`에 이 경로가 들어 있다.

## 2. Docker 설정

학생 컨테이너끼리 서로 접속하지 못하게 한다.

```sh
sudo cp deploy/docker-daemon.json /etc/docker/daemon.json
sudo systemctl restart docker
```

## 3. 파일 올리기

로컬에서:

```sh
./gradlew bootJar
ssh ubuntu@<서버> 'mkdir -p ~/swunion'
scp build/libs/lab-0.0.1-SNAPSHOT.jar ubuntu@<서버>:~/swunion/lab.jar
scp -r docker scripts ubuntu@<서버>:~/swunion/
scp .env ubuntu@<서버>:~/swunion/.env
scp deploy/lab.env.example ubuntu@<서버>:~/swunion/lab.env
```

`bootJar`는 `web/`에서 `pnpm install --frozen-lockfile`과 `pnpm build`를 돌려 나온 `web/out`을 jar에 담는다. 처음에는 패키지를 받느라 몇 분 걸린다. 화면 파일은 jar 안에 있어서 따로 올릴 것이 없다.

서버에서 `~/swunion/lab.env`의 값을 고치고, 두 파일을 본인만 읽게 한다.

```sh
chmod 600 ~/swunion/.env ~/swunion/lab.env
```

| 값 | 설명 |
| --- | --- |
| `PUBLIC_BASE_URL` | `https://your-name.duckdns.org`. 학생 화면 링크와 내 PC 설치 명령이 이 주소를 쓴다. |
| `IDE_BIND_ADDRESS` | `127.0.0.1`. 학생 IDE 포트를 서버 밖에 열지 않는다. |
| `IDE_URL_TEMPLATE` | `{base}/ide/{port}/`. 학생 IDE를 HTTPS 주소 아래 경로로 연다. |
| `INSTRUCTOR_PASSWORD` | 강사 로그인 비밀번호. 강한 값으로 정한다. 학생 컨테이너가 서버에 닿으므로 기본값(`instructor`)을 쓰면 안 된다. |
| `CONTAINER_MEMORY_MB` | 학생 컨테이너 하나의 메모리 상한. 기본 800. |

## 4. 기본 이미지

```sh
cd ~/swunion
sg docker -c "docker build -t swunion/lab-base:2 docker/lab-base"
sg docker -c "docker run --rm --entrypoint code-server swunion/lab-base:2 --list-extensions"
```

목록에 `swunion.swunion-helper`와 `ms-python.python`이 나오면 된다. 서버 CPU 종류에 맞게 서버에서 빌드한다.

## 5. 방화벽과 서비스

```sh
sudo cp deploy/swunion-firewall.sh /usr/local/sbin/ && sudo chmod 755 /usr/local/sbin/swunion-firewall.sh
sudo cp deploy/swunion-firewall.service deploy/swunion-lab.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now swunion-firewall.service swunion-lab.service
```

방화벽 스크립트가 거는 규칙:

- 학생 컨테이너에서 EC2 메타데이터(`169.254.169.254`)로 가는 연결을 막는다. 막지 않으면 학생 코드가 서버에 붙은 AWS 권한을 가져갈 수 있다.
- 학생 컨테이너에서 서버로는 8080만 허용한다. SSH 같은 다른 포트에는 닿지 못한다.
- 8080은 서버 자신과 학생 컨테이너에서만 받는다. 바깥 요청은 Caddy를 거친다.

Docker를 재시작해도 규칙이 다시 걸리도록 서비스로 등록한다.

## 6. Caddy

`deploy/Caddyfile`의 `your-name.duckdns.org`를 실제 도메인으로 바꾼 뒤:

```sh
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl enable caddy && sudo systemctl restart caddy
```

Caddy가 Let's Encrypt 인증서를 자동으로 받고 갱신한다. 도메인이 서버를 가리키고 80 포트가 열려 있어야 한다. `/ide/<포트>/`는 Docker가 고르는 호스트 포트 범위(32768~60999)만 받는다.

IDE는 강사 화면과 같은 주소 아래에 있다. 그래서 `/ide/<포트>/` 요청에서는 이 앱의 쿠키(`JSESSIONID`, `XSRF-TOKEN`)를 떼고 학생 컨테이너로 넘긴다. code-server의 `code-server-session` 같은 다른 쿠키는 그대로 간다.

## 7. 확인

- `https://your-name.duckdns.org/join`이 200, `/instructor`는 `/instructor/login?next=...`으로 302, `/instructor/login`이 200, `/api/courses`가 로그인 없이 401이다.
- 바깥에서 `http://<서버>:8080`으로는 접속되지 않는다.
- `/instructor/login`에서 강사로 로그인해 수업을 만든다. 학생은 `/join`에서 수업 코드, 이름, PIN으로 입장해 "IDE 열기"를 누른다. IDE 왼쪽의 "실습 도우미"에 환경 상태 "정상"이 나온다.
- 학생 컨테이너 안에서 메타데이터가 막혔는지 본다.

```sh
C=$(sg docker -c "docker ps -q" | head -1)
sg docker -c "docker exec -u coder $C curl -s -m 4 -o /dev/null -w '%{http_code}' http://169.254.169.254/latest/meta-data/"
```

`000`이 나오면 막힌 것이다.

## 코드를 바꾼 뒤 다시 배포

- 서버나 화면(`web/`) 코드가 바뀌었으면: `./gradlew bootJar`로 jar를 만들어 올리고 `sudo systemctl restart swunion-lab`.
- `docker/lab-base`가 바뀌었으면: 폴더를 올리고 4번처럼 기본 이미지를 다시 빌드한다. 수업 이미지는 수업을 만들 때 굳어지므로, 새로 만드는 수업부터 적용된다.

## 서버 비우기

수업, 학생 코드, 기록이 모두 지워진다.

```sh
sudo systemctl stop swunion-lab
sg docker -c 'docker rm -f $(docker ps -aq)'
sg docker -c 'docker volume rm $(docker volume ls -q)'
sg docker -c 'docker rmi -f $(docker images --format "{{.Repository}}:{{.Tag}}" | grep ^swunion/course-)'
rm -rf ~/swunion/data
sudo systemctl start swunion-lab
```

## 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| 실습 도우미 패널이 흰 화면 | IDE를 http 주소로 열었다. 학생 실습 화면을 새로고침해 `https://.../ide/<포트>/` 링크로 연다. `IDE_URL_TEMPLATE` 값을 확인한다. |
| 실습 도우미 아이콘이 없음 | 기본 이미지 `:2` 전에 만든 수업이다. 새 수업을 만든다. |
| 패널에 "서버에 연결하지 못했습니다" | `systemctl status swunion-lab`, `sudo iptables -S SWUNION-IN`에 8080 허용 줄이 있는지 본다. |
| 인증서를 받지 못함 | 도메인이 서버 공인 IP를 가리키는지(`dig +short <도메인>`), 80 포트가 열려 있는지 본다. `journalctl -u caddy`에 이유가 남는다. |
| 수업 발급이 FAILED | `journalctl -u swunion-lab`. 기본 이미지가 없거나 uv가 `PATH`에 없을 때가 많다. |

## 알려진 한계

- 강사 계정은 하나다.
- 강사 로그인에는 시도 횟수 제한이 없다. 비밀번호를 길게 정한다.
- 로그인 세션은 마지막 요청 뒤 8시간 유지된다. 대시보드를 열어 두면 3초마다 요청하므로 끊기지 않는다. 서버를 다시 켜면 다시 로그인한다.
- 학생 IDE가 강사 화면과 같은 출처(`/ide/<포트>/`)에서 열린다. 학생이 자기 IDE에서 띄운 페이지는 강사 화면과 같은 출처에서 돈다. 강사는 로그인한 채로 학생 IDE 링크를 열지 않는다. Caddy가 이 앱의 쿠키를 IDE로 넘기지는 않지만, 근본 해결은 IDE를 별도 하위 도메인에서 내보내는 것이다.
- 서버를 두 대 이상 띄우면 안 된다. 수업과 세션 잠금이 서버 메모리에 있다.
- 학생 컨테이너의 인터넷은 열려 있다. 추가 패키지 설치를 위해서다.
- 컨테이너는 Docker 기본 격리만 쓴다. 실제 수업 전에는 gVisor 같은 강화된 격리를 검토한다.
