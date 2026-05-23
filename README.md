# Vibe Coder — Server

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/siamakerlab/vibe-coder-server)](https://hub.docker.com/r/siamakerlab/vibe-coder-server)

> **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만들어내는
> "외부 접근 가능한 개발머신" 그 자체. 서버 PC 에 도커 컨테이너 하나
> 띄우면 브라우저로 바로 로그인해서 프로젝트 생성 · 프롬프트 전송 ·
> Gradle 빌드 · APK 다운로드까지 끝낸다.
>
> 안드로이드 앱(`vibe-coder-android`, 별도 리포) 은 같은 서버를 가리키는
> **부가 클라이언트** 일 뿐, 없어도 모든 기능을 사용할 수 있다.

이 리포는 그 도커 앱의 본체(Ktor 서버) 와 운영용 웹 UI 를 보유한다.
Claude Code CLI / Gradle Wrapper / Git CLI / 파일 관리 자식 프로세스를
모두 서버가 소유하고, 로그는 WebSocket 으로 모든 클라이언트(브라우저 ·
Android 앱) 에 스트림한다.

## 리포 구성

```
vibe-coder-server/
├─ shared/              # JVM 라이브러리 — @Serializable DTO / ApiPath / WsFrame
├─ server/              # Ktor 서버 (Netty), SQLite(Exposed),
│                       #   Claude/Gradle/Git 자식 프로세스, WS 로그 허브,
│                       #   Admin 웹 (SSR HTML)
└─ docker/              # 슬림 Docker 이미지 + compose + vibe-doctor
```

**짝 리포**: `vibe-coder-android` (별도 git 저장소).
주소: `ssh://git@gitea.wody.kr:2929/wody/vibe-coder-android.git`
두 리포는 `:shared` 모듈의 동일 사본을 통해 wire-level 호환을 유지합니다
(`ApiPath` / DTO / `WsFrame` 변경 시 양쪽 함께 갱신).

## 빌드 매트릭스

| Layer | Version |
|---|---|
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| SQLite JDBC | 3.46.1.3 |
| JDK toolchain | 17 |

## 빌드 / 실행

### 로컬 실행

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server --workspace ./workspace
```

부팅 후 콘솔에 다음이 표시됩니다:

```
>>> Vibe Coder Server started
>>> URL         : http://192.168.0.10:17880
```

브라우저로 그 URL 에 접속하면 곧바로 셋업 화면(첫 부팅) 또는 로그인
화면(이미 설정됨) 이 뜬다. 안드로이드 앱(`vibe-coder-android`) 도
같은 URL + username/password 로 로그인할 수 있지만 필수는 아니다.

### 웹만으로 끝낼 수 있는 작업 (v0.6.3+)

| 경로 | 용도 |
|---|---|
| `/` | 대시보드 (서버/환경/활동 요약) |
| `/projects` | 프로젝트 목록 + 새 프로젝트 등록 |
| `/projects/{id}` | 프로젝트 상세, 최근 빌드 |
| `/projects/{id}/console` | Claude 프롬프트 입력 + 실시간 로그 (WebSocket) + 슬래시 chip |
| `/projects/{id}/builds` | Debug 빌드 큐 등록 + APK 다운로드 |
| `/projects/{id}/builds/{buildId}` | 빌드 상세 + 실시간 로그 (WebSocket) + 취소 |
| `/projects/{id}/files` | 파일 업로드 / 다운로드 / 삭제 |
| `/projects/{id}/git` | git status / diff / log (읽기 전용) |
| `/env-setup` | 빌드환경 (JDK / SDK / Claude 로그인) 상태 + 설치 안내 |
| `/settings` · `/devices` · `/password` | 운영 설정 / 디바이스 / 비밀번호 |
| `/login` · `/setup` · `/logout` | 인증 |

v0.4.2 부터 별도 `/admin/*` prefix 없이 모두 루트 바로 아래에 평탄화되어
있다. 구버전 `/admin/...` 경로는 영구 리다이렉트 호환층으로 유지된다
(v0.6.3 에서 제거 예정).

### Docker 실행 (권장)

가장 빠르고 안전한 설치 경로입니다. Docker 가 설치된 어떤 OS (Linux / macOS /
Windows + WSL2) 든 똑같이 동작합니다.

#### 한 줄 quick-start

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# .env 에서 PUID/PGID (id -u; id -g) / 포트 조정 후 (기본값으로도 동작)
docker compose up -d

# ① 첫 부팅 후 admin 셋업: 브라우저 → http://<PC IP>:17880/setup
# ② 좌측 nav → 빌드환경 → "⚡ 모두 설치/업데이트" 클릭 (Android SDK 다운로드 5~15분)
# ③ 빌드환경 → "Claude 로그인" 카드 → 옵션 0 ~ 3 중 편한 방식 선택
```

#### 직접 작성하는 최소 docker-compose.yaml (v0.7.0+)

```yaml
name: vibe-coder
services:
  vibe-coder-server:
    image: siamakerlab/vibe-coder-server:0.7.0
    container_name: vibe-coder-server
    restart: unless-stopped
    environment:
      PUID: "1000"         # id -u 결과
      PGID: "1000"         # id -g 결과
      TZ: "Asia/Seoul"
      JAVA_OPTS: "-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"
      # 첫 부팅 시 admin 자동 생성 (선택 — 미설정 시 /setup 화면)
      # VIBECODER_ADMIN_USERNAME: "admin"
      # VIBECODER_ADMIN_PASSWORD: "ChangeMe123"
    ports:
      - "17880:17880"
    volumes:
      # v0.7.0 — 모든 영구 데이터를 한 호스트 디렉토리에 통합.
      # 이 ./vibe-coder-data/ 하나만 tar 백업하면 워크스페이스 + DB +
      # Android SDK + Gradle 캐시 + MCP + Playwright + Claude 인증까지 보존.
      - ./vibe-coder-data/workspace:/workspace
      - ./vibe-coder-data/server:/data
      - ./vibe-coder-data/dev-tools/android-sdk:/opt/android-sdk
      - ./vibe-coder-data/dev-tools/gradle:/home/vibe/.gradle
      - ./vibe-coder-data/dev-tools/npm-global:/home/vibe/.local
      - ./vibe-coder-data/dev-tools/npm-cache:/home/vibe/.npm
      - ./vibe-coder-data/dev-tools/playwright:/home/vibe/.cache/ms-playwright
      - ./vibe-coder-data/dev-tools/config:/home/vibe/.config
      - ./vibe-coder-data/claude:/home/vibe/.claude
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:17880/health"]
      interval: 30s
      timeout: 5s
      start_period: 20s
      retries: 3
```

#### 자주 쓰는 운영 명령

```bash
docker compose logs -f vibe-coder-server         # 실시간 로그
docker compose restart vibe-coder-server         # 재시작
docker exec -it vibe-coder-server bash           # 쉘 진입 (root)
docker exec -it --user vibe vibe-coder-server claude --version  # vibe 사용자로 실행

# 이미지 업그레이드 (데이터 보존)
docker compose pull
docker compose up -d --force-recreate
```

자세한 가이드(마이그레이션 / 트러블슈팅 / 빌드 푸시) 는 `docker/README.md` 참고.

### 빌드환경은 이미지를 갈아끼워도 보존됩니다 ✅

v0.7.0 부터 모든 영구 데이터가 **호스트 한 디렉토리** (`./vibe-coder-data/`) 에
모입니다. 이미지 업그레이드 / 컨테이너 재생성 시에도 사라지지 않습니다.

| 데이터 | 호스트 경로 | 컨테이너 경로 | recreate 시 |
|---|---|---|---|
| 프로젝트 소스 + APK | `./vibe-coder-data/workspace/` | `/workspace` | ✅ 보존 |
| SQLite DB + 서버 로그 | `./vibe-coder-data/server/` | `/data` | ✅ 보존 |
| Android SDK (3~4 GB) | `./vibe-coder-data/dev-tools/android-sdk/` | `/opt/android-sdk` | ✅ 보존 |
| Gradle 의존성 캐시 (1~2 GB) | `./vibe-coder-data/dev-tools/gradle/` | `/home/vibe/.gradle` | ✅ 보존 |
| MCP server 패키지 (`npm -g`) | `./vibe-coder-data/dev-tools/npm-global/` | `/home/vibe/.local` | ✅ 보존 |
| npx 캐시 | `./vibe-coder-data/dev-tools/npm-cache/` | `/home/vibe/.npm` | ✅ 보존 |
| Playwright 브라우저 (선택) | `./vibe-coder-data/dev-tools/playwright/` | `/home/vibe/.cache/ms-playwright` | ✅ 보존 |
| 기타 도구 설정 | `./vibe-coder-data/dev-tools/config/` | `/home/vibe/.config` | ✅ 보존 |
| Claude 인증 (OAuth/API key/MCP 등록) | `./vibe-coder-data/claude/` | `/home/vibe/.claude` | ✅ 보존 |
| **서버 본체** (Ktor + Claude CLI + JDK + Node) | 이미지 내장 | — | 🔄 새 이미지로 교체 |

```bash
# 백업 (한 줄)
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/

# 다른 PC 로 이전
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

⚠️ **`docker compose down -v` 는 named volume 까지 삭제** 합니다. v0.7.0 신구조
는 named volume 미사용(bind mount) 이지만, 이전 버전에서 마이그레이션 중인
환경에선 여전히 주의. 일반 업그레이드 시엔 `up -d --force-recreate` 만 쓰세요.

> **v0.6.x → v0.7.0 마이그레이션**: 기존 named volume (`vibe-android-sdk`,
> `vibe-gradle-cache`) 데이터를 새 디렉토리로 옮기는 단계별 명령은
> `docker/README.md` 의 "v0.7.0 마이그레이션" 섹션 참고.

## 인증 (v0.4.0+)

- `/api/auth/setup` — 첫 부팅 시 admin 계정 생성 (DB 에 admin 이 없을 때만).
- `/api/auth/login` — username + password → bearer token + `vibe_session` 쿠키.
- `/api/auth/password` — 현재 비밀번호 확인 후 변경.
- `/api/auth/pair` — **deprecated** (admin 존재 시 410 응답).

토큰은 BCrypt cost 12 hash 만 DB 에 저장됩니다. 10 회 연속 실패 시
15 분 잠금. timing-attack 대응으로 dummy verify 도 수행.

## 보안 경계 (MVP)

- 워크스페이스 외부 경로 거부 (`PathSafety.normalizeAndCheck`).
- 토큰 hash-only 저장, 클라이언트에는 발급 시점에 1 회만 평문 노출.
- WebSocket 인증은 **첫 메시지** (`{"type":"auth","token":"..."}`) 로 수행 —
  URL 에 토큰을 싣지 않음.
- 업로드 확장자 blacklist: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- raw-shell UI 없음. `git push` / `git reset --hard` / release 서명 없음.
- 모든 외부 명령은 하드 타임아웃 + 취소 시 `destroyForcibly`.

## 라이센스

이 프로젝트는 **GNU Affero General Public License v3.0 (AGPL-3.0)** 으로
배포됩니다. 전문은 [LICENSE](LICENSE) 파일 참고.

요지:
- 자유롭게 사용 / 수정 / 재배포 가능.
- 수정본을 네트워크 너머로 서비스(SaaS) 한다면 **수정 소스 코드 공개 의무**.
- 파생 프로젝트도 같은 AGPL-3.0 로 배포해야 함.

상업적 사용이 가능하나 copyleft 의무를 인지하고 진행하세요.

## 베이스라인 결함 회수 이력

분리 시점(v0.4.0 main) 에 보고됐던 컴파일 결함 3건 + 비차단
deprecation 2건 + `.gitignore` 패턴 결함 1건은 모두 v0.4.1 에서 회수.
상세 내용은 `CHANGELOG.md` 의 v0.4.1 섹션 참고.

- `ApkFinder.kt` — Kotlin 2.2 에서 제거된 `kotlin.streams.toList` import +
  KDoc 안의 `/*` 시퀀스가 nested comment 로 해석되는 신택스 에러.
- `ServerActionHandler.kt` — 존재하지 않는 `builds.submitDebug` 호출을
  실제 메소드 `builds.enqueueDebug` 로 정정.
- `auth/AuthPlugin.kt` — Ktor 3.x deprecated `Principal` 인터페이스 제거.
- `files/FileRoutes.kt` — `PartData.streamProvider` → `provider()` 마이그.
- `.gitignore` — `build/` 패턴이 `server/src/.../server/build/` 패키지까지
  무시하여 4 개 핵심 소스 파일이 untracked 상태였음. 패턴을 좁히고 누락
  파일을 정상 등록.
