# Vibe Coder Server — Docker 이미지

> **1인 LAN 페어링 도구**용 슬림 Docker 이미지.
> Android SDK / Claude 인증 등 무거운 컴포넌트는 이미지에 박지 않고,
> 컨테이너 부팅 후 `vibe-doctor` 가 사용자 동의 하에 볼륨으로 다운로드합니다.

## 빠른 시작 (3분)

```bash
# 1) 이미지 받기 (또는 로컬 빌드)
docker pull siamakerlab/vibe-coder-server:0.7.0

# 2) compose 파일과 .env 복사
mkdir -p ~/vibe-coder && cd ~/vibe-coder
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/.env.example -o .env
# .env 를 편집기로 열어 PUID/PGID/포트 조정

# 3) 부팅 — ./vibe-coder-data/ 가 자동 생성됩니다
docker compose up -d

# 4) admin 웹 셋업 (브라우저)
#    http://<PC IP>:17880/admin → 첫 비밀번호 설정 → 빌드환경 페이지

# 5) 빌드 환경 다운로드 (Android SDK 등)
#    웹 UI 의 "⚡ 모두 설치/업데이트" 버튼이 표준입니다.
#    터미널로 진행하려면:
docker exec -it vibe-coder vibe-doctor
```

이후 Android 앱에서 같은 서버 URL + username/password로 로그인.

---

## 이미지 구성

| 레이어 | 내용 | 크기 |
|---|---|---|
| Ubuntu 22.04 (slim) | base | ~30MB |
| OpenJDK 17 (JRE) | vibe-coder 서버 실행 | ~200MB |
| Node 20 LTS + Claude Code CLI | Claude 자식 프로세스 | ~250MB |
| git, curl, unzip, jq, tini, gosu 등 | 빌드 도구 최소셋 | ~80MB |
| vibe-coder 서버 (installDist) | Ktor 본체 | ~50MB |
| **Total** | | **~600MB** |

이미지에 **포함되지 않은 것들** (doctor가 볼륨에 다운로드):

- Android SDK (~3~4GB)
- Gradle 의존성 캐시 (~1~2GB, 첫 빌드 시)
- Claude 인증 자격증명 (호스트 ~/.claude 마운트 권장)
- 선택적 MCP (Playwright Chromium 등)

---

## 환경설정 (`.env`)

`.env.example`을 복사하여 `.env`로 사용합니다. 주요 항목:

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:0.7.0` | pull 할 이미지 태그 |
| `PUID` / `PGID` | `1000` / `1000` | 호스트 UID/GID 매칭. `id -u` / `id -g` 로 확인 |
| `VIBE_PORT` | `17880` | 호스트 노출 포트 |
| `VIBE_DATA_ROOT` | `./vibe-coder-data` | **모든 영구 데이터가 들어가는 통합 디렉토리** |
| `VIBE_CLAUDE_DIR` | `${VIBE_DATA_ROOT}/claude` | Claude 인증 디렉토리 — 호스트와 공유하려면 `~/.claude` |
| `VIBECODER_ADMIN_USERNAME` | (미설정) | 첫 부팅 시 admin 자동 생성용 |
| `VIBECODER_ADMIN_PASSWORD` | (미설정) | 위와 한 쌍. 부팅 직후 변경 권장 |
| `JAVA_OPTS` | `-Xmx2g …` | JVM 힙. 호스트 RAM 보고 조정 |

### 볼륨 구조 (v0.7.0 통합)

모든 영구 데이터는 **호스트 한 디렉토리** (`./vibe-coder-data`) 안에 모입니다.
이 디렉토리만 백업하면 워크스페이스 + DB + Android SDK + Gradle 캐시 + MCP
+ Playwright + Claude 인증까지 전부 보존됩니다.

```
${VIBE_DATA_ROOT}/                          컨테이너
─────────────────                           ─────────────
├── workspace/                  →  /workspace
├── server/                     →  /data                              (SQLite/로그)
├── dev-tools/
│   ├── android-sdk/            →  /opt/android-sdk                   (3~4GB)
│   ├── gradle/                 →  /home/vibe/.gradle                 (1~2GB)
│   ├── npm-global/             →  /home/vibe/.local                  (MCP `npm -g`)
│   ├── npm-cache/              →  /home/vibe/.npm                    (npx 캐시)
│   ├── playwright/             →  /home/vibe/.cache/ms-playwright    (선택)
│   └── config/                 →  /home/vibe/.config                 (도구 설정)
└── claude/                     →  /home/vibe/.claude                 (OAuth/MCP 등록)
```

`dev-tools/` 안의 6개 디렉토리는 모두 "한 번 다운로드 → 영구 보존" 도구
캐시입니다. **이미지 업그레이드(`docker compose pull && up -d`) 후에도
절대 사라지지 않습니다.**

### 백업 / 이전

```bash
# 백업
docker compose stop
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/

# 다른 PC로 이전
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost
cd ~/vibe-coder
tar xzf vibe-coder-data-*.tar.gz
docker compose up -d   # 기존 데이터 그대로 복원됨
```

### v0.7.0 마이그레이션 (이전 사용자)

`v0.6.x` 까지 사용하던 사용자는 **Android SDK 와 Gradle 캐시가 named volume
(`vibe-android-sdk`, `vibe-gradle-cache`) 에 저장**되어 있고, MCP 는 시스템
디렉토리에 있어 이미지 업그레이드 시 사라졌습니다. v0.7.0 부터는 모두 bind
mount 로 통일됩니다.

**옵션 1 — 깔끔하게 새로 시작 (권장, 5~15분):**

```bash
# 1) 기존 컨테이너 중지 (named volume 은 보존됨)
docker compose down

# 2) compose.yml + .env 를 새 버전으로 교체
curl -fsSL .../compose.yml -o compose.yml
curl -fsSL .../.env.example -o .env

# 3) 부팅 → 빌드환경 페이지에서 "모두 설치/업데이트" 클릭
docker compose up -d
# → 브라우저: http://<IP>:17880/env-setup
```

**옵션 2 — 기존 named volume 데이터 복사 (대역폭 절약):**

```bash
docker compose down

# Android SDK
docker run --rm \
    -v vibe-coder_vibe-android-sdk:/from \
    -v "$(pwd)/vibe-coder-data/dev-tools/android-sdk":/to \
    alpine sh -c 'cp -a /from/. /to/'

# Gradle 캐시
docker run --rm \
    -v vibe-coder_vibe-gradle-cache:/from \
    -v "$(pwd)/vibe-coder-data/dev-tools/gradle":/to \
    alpine sh -c 'cp -a /from/. /to/'

# Claude 인증 (호스트 ~/.claude 마운트였다면 그대로 두면 됨)
# 통합 디렉토리로 옮기려면:
cp -a ~/.claude vibe-coder-data/claude

docker compose up -d
# 확인 후 named volume 삭제 (선택)
docker volume rm vibe-coder_vibe-android-sdk vibe-coder_vibe-gradle-cache
```

> ⚠ **MCP 는 옵션 1, 2 어느 쪽이든 재설치 필요합니다.** 이전엔 시스템
> 디렉토리(`/usr/local/lib/node_modules`)에 깔려서 이미지 layer 안에만
> 존재했기 때문입니다. v0.7.0 부터는 `/home/vibe/.local` (bind mount) 에
> 떨어지므로 한 번 설치 후 영구 보존됩니다.

---

## doctor

```bash
docker exec -it vibe-coder vibe-doctor              # 인터랙티브 (권장)
docker exec -it vibe-coder vibe-doctor check        # 진단만
docker exec    vibe-coder vibe-doctor install       # 비대화형 일괄 설치
docker exec -it vibe-coder vibe-doctor android      # Android SDK만
docker exec -it vibe-coder vibe-doctor claude       # Claude 인증만
docker exec -it vibe-coder vibe-doctor mcp          # 선택적 MCP만
```

처음 실행 시 다음 순서로 진행됩니다.

1. **환경 진단** — JDK / Node / git / Claude CLI / 워크스페이스 권한
2. **Android SDK 설치** — cmdline-tools (130MB) → 라이선스 자동 수락 → platform-tools + platforms;android-35 + build-tools;35.0.0
3. **Claude 인증** — 호스트 ~/.claude 마운트 권장. 또는 컨테이너 안 `claude login`.
4. **선택적 MCP** — filesystem, sqlite, fetch, playwright 등 (개별 동의)
5. **최종 점검** — 모든 컴포넌트 ✓ 확인

---

## Admin 웹

`http://<PC IP>:17880/admin`

| 페이지 | 기능 |
|---|---|
| `/admin/setup` | 첫 부팅 시 admin 계정 생성 |
| `/admin/login` | 로그인 |
| `/admin` | 대시보드 (서버 상태, 환경 진단, 최근 빌드) |
| `/admin/settings` | server.yml 항목 GUI 편집 |
| `/admin/password` | 비밀번호 변경 |
| `/admin/devices` | 페어링된 디바이스 목록 / revoke |

Android 앱은 같은 username/password로 로그인합니다.

---

## 트러블슈팅

### "Permission denied" — 볼륨 권한 오류

`PUID` / `PGID` 가 호스트 사용자와 일치하지 않을 때 발생.

```bash
id -u; id -g                   # 호스트 UID/GID 확인
# .env의 PUID/PGID를 이 값으로 변경 후
docker compose up -d --force-recreate
```

### "Build failed: SDK location not found"

doctor 미실행. `docker exec -it vibe-coder vibe-doctor android` 실행.

### Claude가 인증 안 됨

호스트 `~/.claude` 마운트 권장. 또는 `docker exec -it --user vibe vibe-coder claude login`.

### 빌드가 느림

`vibe-gradle-cache` 볼륨이 첫 빌드에서 채워집니다. 2번째 빌드부터 빨라짐.
RAM 여유가 있다면 `.env`에서 `JAVA_OPTS=-Xmx8g` 등으로 늘리세요.

### Windows / WSL2에서 사용 시

프로젝트 소스를 **WSL2 안의 리눅스 파일시스템**(`/home/...`)에 두세요.
`/mnt/c/...` 의 윈도우 디스크 경로를 마운트하면 빌드 I/O 가 5~20배 느려집니다.

---

## 빌드 / 푸시 (메인테이너용)

### 일반 commit 푸시 (amd64-only, 빠름 · v0.6.3+ 기본)

```bash
docker buildx create --name vibe-builder --driver docker-container --use  # 1회만
docker buildx build \
    --platform linux/amd64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<버전> \
    -t siamakerlab/vibe-coder-server:latest \
    --push \
    .
```

amd64-only 는 2~3분 안에 끝납니다. arm64 emulation 빌드를 생략하므로
잦은 개발 push 에 적합. Apple Silicon Mac / Raspberry Pi 등 ARM 호스트에서도
Docker Desktop 의 자동 emulation 으로 실행은 가능합니다 (느릴 뿐).

### 마일스톤 multi-arch 푸시 (v0.7.0, v1.0.0 같은 큰 릴리즈)

```bash
docker buildx build \
    --platform linux/amd64,linux/arm64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<버전> \
    -t siamakerlab/vibe-coder-server:latest \
    --push \
    .
```

cross-compile 이라 10~15분 소요. ARM 호스트에서 native 속도가 필요한 사용자
대응용. CHANGELOG 의 "## [버전] 배포" 항목에 `linux/amd64 + linux/arm64`
임을 명시.

---

## 보안 메모

- 이 이미지는 **LAN 내부 전용**입니다. 공인 IP에 노출하지 마세요.
- Admin 비밀번호 정책: 길이 ≥ 8, 영문+숫자 혼합.
- 페어링 토큰 / 비밀번호는 DB에 **hash만** 저장됩니다 (BCrypt cost 12, SHA-256).
- `.env`에 admin 비밀번호를 plain text로 둘 경우, 부팅 직후 `/admin/password`에서 변경하세요.
