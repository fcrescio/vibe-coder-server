# Changelog — vibe-coder-server

All notable changes to the server component will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

v0.4.0 까지는 `vibe-coder` 모노레포의 단일 CHANGELOG 였고, v0.4.1 부터
서버/안드로이드 두 리포로 분리되어 각 리포가 독립 changelog 를 갖는다.
Android 클라이언트 이력은 `vibe-coder-android` 리포의 CHANGELOG 참고.

## [Unreleased]

## [0.5.0] - 2026-05-23

### Added — 웹만으로 완결되는 SSR 콘솔 (Standalone 도커 앱 자아 확립)

안드로이드 앱 없이도 브라우저 하나로 **프로젝트 등록 → Claude 프롬프트 →
Gradle 빌드 → APK 다운로드** 까지 끝나도록 SSR 화면을 추가. v0.4.2 에서
선언했던 비전의 본 단계.

| 새 경로 | 기능 |
|---|---|
| `GET /projects` | 프로젝트 목록 + "새 프로젝트" 등록 폼 (`projectId` / `appName` / `packageName`) |
| `POST /projects` | `ProjectService.register` 호출. 워크스페이스에 빈 폴더 + `CLAUDE.md` 템플릿 생성. 성공 시 `/projects/{id}` 로 redirect |
| `GET /projects/{id}` | 프로젝트 상세 — 패키지/모듈/소스 경로/최근 빌드 5건 + 콘솔/빌드 진입 링크 + 메타데이터 삭제 |
| `POST /projects/{id}/delete` | 프로젝트 DB row 삭제 (워크스페이스 폴더 보존) |
| `GET /projects/{id}/console` | Claude 프롬프트 입력 + 실시간 로그 뷰. WebSocket `/ws/projects/{id}/console/logs` 자동 연결, replay + live frame 렌더. Ctrl+Enter 로 프롬프트 전송 |
| `POST /projects/{id}/console/new` | `ClaudeSessionManager.startNew` — 현 세션 종료, 다음 프롬프트가 새 대화 시작 |
| `GET /projects/{id}/builds` | 빌드 목록 + 상태 배지 + APK 다운로드 링크 (`/api/projects/{id}/artifacts/{aid}/download`) |
| `POST /projects/{id}/builds` | `BuildService.enqueueDebug` 큐 등록 |

추가 사항:

- **사이드바 nav 에 "프로젝트" 링크 추가** — 대시보드와 같은 깊이의 일급 메뉴.
- **WebSocket 인증 호환** — 콘솔 JS 는 `vibe_session` 쿠키 값을 그대로 첫
  `Auth` 프레임의 token 으로 보냄. 서버 `installAuth` 가 쿠키도 토큰 운반체로
  받아주는 v0.4.0 의 변경 덕분에 별도 토큰 발급 절차 없이 작동.
- **JS 는 인라인** (외부 빌드 파이프라인 없음, Node 의존 없음). LAN-only
  도구 철학 유지.
- **CSS** — `admin.css` 에 `.console-log` / `.prompt-form` / 액션 컬러
  팔레트(`assistant=green` / `tool=amber` / `user=blue` / `err=red` /
  `sys=gray`) 추가.

### Refactored — `AdminTemplates.shell()` internal 노출

새 페이지(`WebProjectTemplates`) 가 동일 레이아웃 셸을 사용할 수 있도록
`private fun shell` → `internal fun shell`. nav link 매칭도 새 경로
체계 (`/projects` 등) 를 인식하도록 갱신.

`requireSessionOrRedirect` / `WebSession` 타입도 `internal` 로 풀어 같은
모듈 내 다른 라우트 파일이 재사용 가능.

### Module 변경

- `server/Module.kt` — `webProjectRoutes(...)` 등록. 의존성:
  `ProjectService` / `BuildService` / `BuildRepository` / `ArtifactRepository`
  / `ClaudeSessionManager` / `LogHub`. `AdminRoutesDeps` 는 `adminRoutes` 와
  `webProjectRoutes` 가 같은 인스턴스를 공유 (세션 검증 일관성).

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작. 새 페이지들은 기존 `/api/*` REST + `/ws/*` WebSocket 위에
얇은 SSR + JS 레이어로 얹혀 있다.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.0` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md`, `README.md` 의 태그 동기.

### 다음 단계 (v0.5.1+)

- `/projects/{id}/files` — 워크스페이스 파일 업로드/다운로드 UI.
- `/projects/{id}/git` — git status/diff/log 뷰.
- 콘솔 입력에서 액션 chip (status / cost / model / clear 등) 호출 버튼.
- 빌드 로그 실시간 뷰 (현재는 콘솔에 ConsoleSystem 으로 흘러가지만 별도
  탭으로 분리 검토).

## [0.4.2] - 2026-05-23

### Changed — 정체성 선언: Standalone 도커 앱

`vibe-coder-server` 의 자아를 명시한다:

> **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만들어내는
> 외부 접근 가능한 개발머신 그 자체. 브라우저로 바로 로그인해 프로젝트
> 생성 · 프롬프트 전송 · Gradle 빌드 · APK 다운로드까지 끝낸다.
> 안드로이드 앱은 같은 서버를 가리키는 부가 클라이언트일 뿐 없어도
> 모든 기능을 쓸 수 있어야 한다.

이번 릴리즈는 그 비전의 **첫 단계**로 웹 UI 의 경로 구조를 평탄화. 실제
프로젝트 / 콘솔 / 빌드 화면 추가는 Phase 2 (v0.5.0) 에서 진행한다.

`README.md` / `CLAUDE.md` 톤도 동일한 방향으로 다듬음.

### Changed — Web URL 평탄화: `/admin/*` prefix 제거 (web-side breaking)

별도 `admin` 영역 개념을 제거하고 모든 SSR 화면을 루트 레벨로 이동.
사용자가 도메인 / 으로 접속하면 별도 추가 입력 없이 곧바로 첫 화면
(셋업 / 로그인 / 대시보드 자동 분기) 이 뜬다.

| v0.4.1 | v0.4.2+ |
|---|---|
| `/admin` | `/` |
| `/admin/login` | `/login` |
| `/admin/setup` | `/setup` |
| `/admin/settings` | `/settings` |
| `/admin/devices` | `/devices` |
| `/admin/password` | `/password` |
| `/admin/logout` | `/logout` |
| `/admin/static/admin.css` | `/static/admin.css` |

호환: `GET /admin{path...}` 핸들러가 같은 경로의 루트 버전으로
**HTTP 301 영구 리다이렉트** 한다. 북마크 · 구버전 안드로이드 앱
사용자 영향 없음. v0.6.0 에서 호환층 제거 예정.

영향 받지 않음:
- 모든 REST API (`/api/*`) — 안드로이드 앱과 wire-level 호환 그대로.
- WebSocket (`/ws/*`)
- `/health` 헬스 프로브 (Docker HEALTHCHECK)

구현:

- `server/admin/AdminRoutes.kt` — `route("/admin") { ... }` 블록 해체,
  모든 라우트를 root level 로. 함수명 `adminRoutes` 는 호출부 변경
  최소화를 위해 유지 (내부적으로 더 이상 admin 전용이 아님 → 향후
  `webRoutes` 로 rename 검토).
- `staticResources("/admin/static", ...)` → `staticResources("/static", ...)`.
- 모든 `respondRedirect("/admin/...")` 호출을 평탄 URL 로 정정.
- `requireSessionOrRedirect` 의 `next=` 파라미터 검증을 `startsWith("/admin")`
  → `startsWith("/") && !startsWith("//")` 로 강화. open-redirect 방지.
- `server/admin/AdminTemplates.kt` — `<link href=>` , 폼 `action=`, nav
  `href=`, `errorPage` 의 "대시보드로" 링크 전부 평탄 URL 로 정정.
  `navHtml` 의 `active` 클래스 매칭도 새 경로 체계로 갱신.
- `server/ServerMain.kt` 부팅 배너 — "Admin URL" 표기 제거, 단일 URL.

### Fixed — KDoc nested-comment 함정 (재발)

`AdminRoutes.kt` 의 새 KDoc 본문에 `admin/*` 표현을 그대로 적었더니
v0.4.1 의 `ApkFinder.kt` 와 동일한 K2 컴파일러 nested-comment 해석으로
`Unclosed comment` 에러 발생. 백틱 코드 표기 (`admin`) 로 회피.
앞으로 KDoc 내부에서 path glob (`*`, `/*`) 표현 사용 금지를 컨벤션화.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.4.2` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그 동기.

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작.

## [0.4.1] - 2026-05-23

### Infrastructure — 모노레포 → 2 리포 분리

`vibe-coder` 단일 저장소를 `vibe-coder-server` (본 리포) 와
`vibe-coder-android` (별도 리포) 로 분리. 본 리포는 `:server` /
`:shared` / `docker/` / `vibe-doctor` / Admin 웹 / docs 만 보유한다.

- `settings.gradle.kts`: `rootProject.name = "vibe-coder-server"`,
  `:android-app:app` 와 `skipAndroidModule` 옵션 제거. 모듈은
  `:shared` / `:server` 둘만.
- `gradle/libs.versions.toml`: Android 전용 라이브러리(Compose BOM,
  Hilt, AndroidX, Material Icons, DataStore, navigation-compose, Timber,
  espresso, Truth, Ktor client, KSP, AGP) 와 그 plugin alias 들
  (`kotlin-compose`, `android-application`, `ksp`, `hilt`, `ktor`) 제거.
- 루트 `build.gradle.kts`: `kotlin-jvm` / `kotlin-serialization` 만 남김.
- `:shared` 는 `vibe-coder-android` 리포에 **동일 사본**으로 존재하며
  wire-level 호환을 유지하기 위해 변경 시 양쪽 함께 갱신한다.

### Fixed — 베이스라인 결함 + deprecated 일괄 정리

CLAUDE.md §7 에 적혀 있던 split 시점 빌드 결함을 **실제 재현**하여 모두 회수.
이전 사이클에서는 Gradle daemon 캐시 영향으로 통과한 것처럼 보였으나,
`./gradlew clean` 후 정상 재현되었다.

- **`build/ApkFinder.kt`** — Kotlin 2.2 에서 제거된 `import kotlin.streams.toList`
  제거. JDK 16+ 의 `Stream.toList()` 멤버 메소드로 충분. 동시에 KDoc 본문
  `…/build/outputs/apk/debug/*.apk` 안의 `/*` 시퀀스가 K2 컴파일러에 의해
  nested comment 시작으로 해석되어 `Unclosed comment` 신택스 에러를 일으키던
  부분도 KDoc 표현을 재작성해 회피. import 결함이 가려져 있던 동안 함께
  숨어 있던 결함.
- **`actions/ServerActionHandler.kt:55`** — 존재하지 않는 `builds.submitDebug`
  호출을 실제 메소드 `builds.enqueueDebug` 로 정정.
- **`build/BuildService.kt`** — 위 두 결함의 파급으로 보고됐던 타입 추론
  실패는 root cause 해결과 동시에 자동 해소.

같이 발견된 비차단 deprecation 경고 2건도 동일 PR 에서 모더나이즈:

- **`auth/AuthPlugin.kt`** — Ktor 3.x 에서 `Principal` interface 가 deprecated
  (`This interface can be safely removed`). `DevicePrincipal: Principal` 의
  상위 인터페이스 제거. `principal<DevicePrincipal>()` 호출은 그대로 동작.
- **`files/FileRoutes.kt`** — `PartData.FileItem.streamProvider` (blocking
  InputStream) deprecated. `provider().toInputStream()`
  (`io.ktor.utils.io.jvm.javaio.toInputStream`) 로 교체. UploadService 시그니처
  (`InputStream` 입력) 는 그대로 유지.

이외에 split 직후 한 번 발견됐던 `ProcessRunnerTimeoutTest` 의 `@Test fun =
runBlocking { ... }` 시그니처 (Kotlin 2.2 + JUnit 4 거부) 는 0.4.0 시점에
이미 `: Unit` 명시로 정정됨. 회귀 없음.

전체 `./gradlew :server:test` 18 테스트 통과, deprecation warning 0건.

### Fixed — `.gitignore` 패턴이 source 패키지까지 무시하던 문제

`.gitignore` 의 `build/` 패턴이 Gradle output 뿐 아니라 source 트리 안의
패키지 디렉토리 `server/src/main/kotlin/.../server/build/` 까지 매칭하여,
**4개 핵심 파일이 git tracking 에서 누락된 상태였다**:

- `build/ApkFinder.kt`
- `build/BuildRoutes.kt`
- `build/BuildService.kt`
- `build/GradleBuilder.kt`

이 결함이 v0.4.0 분리 시점부터 누적되어 있었기 때문에, "main 체크아웃에선
재현되지 않는다" 고 적었던 이전 CHANGELOG 기록은 잘못된 관찰이었다
(파일 자체가 commit 되지 않아 clone 후에는 컴파일 자체가 불가능).

회수 방식:

- `.gitignore`: `build/` → `**/build/` + `!**/src/**/build/**` + `!**/src/**/build/`
  로 패턴을 좁혀, Gradle output (`server/build/`, `shared/build/`, `/build/`) 만
  무시하고 source 트리 안의 build 패키지는 보존하도록 변경.
- 누락 4개 파일을 git 에 정상 등록.

검증: `git check-ignore -v` 로 `server/build` 는 ignored, `server/src/.../build/ApkFinder.kt`
는 not-ignored 확인.

### Fixed — 로그인 / 셋업 / 에러 페이지 좌측 정렬 깨짐

Admin 웹의 `.layout` 은 `grid-template-columns: 220px 1fr` 고정이라
사이드바를 렌더하지 않는 화면(로그인 / 셋업 / 에러)에서도 좌측 220px 컬럼이
빈 채로 잡혀, `.auth-card` 가 시각적으로 좌측에 치우쳐 보였다.

- `AdminTemplates.shell()` 이 `showNav=false` 일 때 `.layout` 에 `no-nav`
  modifier 클래스를 추가하도록 변경.
- `admin.css` 에 `.layout.no-nav { grid-template-columns: 1fr }` 및
  `.content { width: 100%; justify-self: center; }` 추가. max-width:1200px
  은 유지하되 grid 셀 내에서 가운데 정렬되도록 보강.

### 배포

- Docker Hub 게시: `siamakerlab/vibe-coder-server:0.4.1` / `:latest`
  (linux/amd64 + linux/arm64 멀티아키, 2026-05-23).
  `docker pull siamakerlab/vibe-coder-server:0.4.1` 로 즉시 사용 가능.
  `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그를 `0.4.1` 로 동기.

### Wire change

없음. ApiPath / DTO / WsFrame 변경 없이 서버 내부 수정만.

## [0.4.0] - 2026-05-21

### Added — Docker 이미지 + Admin 웹 + 통합 인증

> 서버를 도커 이미지로 패키징하고, Android 앱 외에 브라우저 admin 웹을
> 통해 초기 셋업 / 비밀번호 변경 / 디바이스 관리가 가능하도록 인증
> 모델을 통합. 설계 문서: `docs/01-plan/admin-web.md`.

**Docker PoC (`docker/`)**
- **슬림 멀티스테이지 Dockerfile**: JDK17 builder + JRE runtime, 약 600MB.
  Android SDK / Gradle 캐시 / Claude 인증은 이미지에 박지 않고 컨테이너
  부팅 후 doctor가 볼륨에 다운로드.
- **vibe-doctor** (`docker/doctor/`): 인터랙티브 셋업 도우미.
  `check` / `install` / `android` / `claude` / `mcp` 서브커맨드.
  Android SDK cmdline-tools 자동 다운로드 + sdkmanager 라이선스 자동
  수락 + manifest.yml 기반 패키지 설치.
- **entrypoint.sh**: 호스트 UID/GID 매칭(`PUID`/`PGID`), 볼륨 소유권 정리,
  `VIBECODER_ADMIN_USERNAME`/`PASSWORD` env 패스스루, Android SDK 누락
  안내. `tini` PID 1 + `gosu` 권한 강등.
- **compose.yml**: 5개 볼륨(workspace/data/android-sdk/gradle-cache/claude),
  헬스체크(`/health`), `.env` 통한 모든 옵션 외부화.
- **docker/.env.example**: 한국어 주석. UID/포트/경로/JVM/admin 부트스트랩.
- **docker/README.md**: pull → compose up → admin → doctor → 앱 로그인까지
  한국어 가이드.

**Shared (`shared/`)**
- `ApiPath`: `AUTH_LOGIN`, `AUTH_SETUP`, `AUTH_SETUP_STATUS`, `AUTH_PASSWORD`,
  `HEALTH` 추가. 기존 `AUTH_PAIR` 등은 deprecated 표기로 유지.
- DTO 추가: `LoginRequestDto`, `LoginResponseDto`, `SetupRequestDto`,
  `ChangePasswordRequestDto`, `SetupStatusDto`. `MeDto`에 `username` 필드.

**Server (`server/`)**
- **`/health`**: 인증 없는 헬스 프로브. Docker HEALTHCHECK / 모니터링용.
- **AdminUsers 테이블 + DeviceRow 확장**: `user_id`/`channel` 컬럼 추가
  (nullable + default, 자동 마이그레이션).
- **`AdminUserRepository`**: 단일 admin 행 CRUD. `count()` / `findById*` /
  `insert` / `touchLogin` / `updatePassword`.
- **`PasswordHasher`**: BCrypt cost 12. `PasswordPolicy`(영문+숫자 8자
  이상), `UsernamePolicy`(3~32자 `[A-Za-z0-9._-]`).
- **`AuthService`**: `setup` / `login` / `changePassword`. 같은 username
  10회 실패 시 15분 잠금. `dummy verify`로 timing-attack 방어.
- **`AuthRoutes`**: `/api/auth/login`, `/api/auth/setup`,
  `/api/auth/setup/status`, `/api/auth/password` 신규. `/api/auth/pair`는
  admin 존재 시 410(`pairing_deprecated`) 반환.
- **`AuthPlugin`**: `Authorization` 헤더 외에 `vibe_session` 쿠키도 토큰
  운반 경로로 인정. 같은 토큰이 두 경로 어느 쪽으로 와도 인증됨.
- **Admin 웹 (`admin/`)**: 서버 사이드 렌더 HTML. `/admin/setup` /
  `/admin/login` / `/admin` (대시보드) / `/admin/settings` /
  `/admin/password` / `/admin/devices` / `/admin/logout`. 외부 CDN 의존
  없는 다크 테마 CSS (`resources/static/admin/admin.css`).
- **`ServerMain.bootstrapAdminFromEnv()`**: 부팅 시 `VIBECODER_ADMIN_*` env
  가 있고 DB에 admin이 없으면 자동 생성. Docker compose 자동화용.
- **부팅 배너**: Admin URL 표시 추가, admin 미존재 시 경고 출력.
  레거시 페어링 코드는 admin 부재 시에만 호환용으로 노출.

**Android (`android-app/app/`)**
- **`ApiService.pair()` → `login()`**: username/password 입력으로 토큰
  발급. `LoginRequestDto`/`LoginResponseDto` 사용.
- **`AuthRepository.login()`**: 시그니처 `(serverUrl, username, password,
  deviceName)`. 기존 `pair()` 제거.
- **`KtorClient.sendWithoutRequest`**: `/api/auth/login`,
  `/api/auth/setup`, `/api/auth/setup/status`, `/health`도 인증 헤더 제외
  대상에 추가.
- **`ConnectScreen`**: 페어링 코드 입력 필드를 username + password 필드로
  교체. 비밀번호는 `PasswordVisualTransformation`. admin 셋업 안내 문구
  추가.
- **`strings.xml`**: `connect_pairing_code` → `connect_username` /
  `connect_password` / `connect_setup_hint`. `connect_button` "Pair" →
  "Sign in".

**빌드 인프라**
- **`settings.gradle.kts`**: `-PskipAndroidModule=true` 옵션 신설.
  Docker 이미지 빌드 시 :android-app:app을 제외하여 AGP/Android SDK 의존성
  없이 `:server`만 빌드.

**검증 (PoC manual)**
- `./gradlew :server:installDist` 통과, BCrypt jar 정상 포함
- 서버 실제 부팅 → `/health` `/api/auth/setup/status` `/admin` 302 →
  setup → login(wrong/correct) → bearer `/me` → cookie `/admin` 대시보드
  → `/api/auth/password` (wrong/correct) → 새 비번 로그인 → 레거시
  `/api/auth/pair` 410 모든 흐름 정상
- `./gradlew :android-app:app:compileDebugKotlin` 통과

## [0.3.0] - 2026-05-21

> v0.2.0의 마지막 deferred 2건 처리: 액션 권한 게이트(FR-11-b) + MCP
> per-tool enumeration. 채팅 콘솔이 host capability 상태를 보고 비가용
> 액션을 자동 비활성화하고, MCP 도구는 `.mcp.json`에 직접 적은 만큼 즉시
> chip으로 노출된다.

### Added — capability gate (FR-11-b)
- **Shared**: `ProjectActionDto`의 모든 sealed 변형에 `requires: List<String>`
  필드 추가. `ActionTreeDto.capabilities: Map<String, Boolean>` 신설.
  `CapabilityKey` 상수 객체 — `BUILD`, `GIT`, `CLAUDE_SESSION`,
  `mcp(server)`.
- **Server**: `actions/CapabilityService` 신설. EnvDiagnostics를 30초 TTL로
  캐시하여 `git` / `claude_session` 상태 계산, `.mcp.json`의 서버 목록을
  `mcp:<name>=true`로 매핑. `build`는 등록된 프로젝트라면 true.
- **Server**: `ProjectActionRoutes.GET /actions`가 응답에 capabilities
  포함. `ProjectActionRegistry.listForProject(projectId, capabilities)`로
  시그니처 확장.
- **Server manifests**: 기본 4개 manifest 갱신 — `build:debug` →
  `requires:["build"]`, `git:*` → `requires:["git"]`, `slash:*` →
  `requires:["claude_session"]`. 정적 텍스트만 다루는 prompt/snippet은
  `requires:[]` 유지.
- **Android**: `QuickActionChips`가 `tree.capabilities` × `action.requires`를
  보고 비가용 chip을 disabled로 렌더. 비활성 chip을 탭/롱탭하면 토스트로
  사유 표시(`cap_unavailable_*` 문자열 키). `strings.xml`에 5개 capability
  사유 메시지 추가.

### Added — MCP per-tool enumeration
- **Server**: `.mcp.json`의 서버 entry에 `tools` 배열을 선언하면 per-tool
  chip 생성. 형식:
  ```json
  {"mcpServers":{"bkit":{
    "command":"...","args":[...],
    "tools":[
      {"name":"bkit_pdca_status","label":"PDCA Status","icon":"Activity"},
      {"name":"bkit_pdca_history"}
    ]}}}
  ```
  `label`/`icon` 생략 시 `name`/"Plug"로 기본화. `argsTemplate`는 JSON
  그대로 통과. `tools`가 없으면 기존 per-server fallback chip 유지.
- **Server**: 자동 생성된 InvokeMcpTool은 `requires:["mcp:<server>"]`를 갖고,
  capability map에서 해당 키가 true일 때만 enabled.
- **Server**: `ProjectActionRegistry.mcpServerNames(projectId)`를 외부에
  노출하여 CapabilityService가 활용.

### Versions
- `versionName` `0.2.2` → `0.3.0` (MINOR: 액션 시스템 신규 기능 — 권한
  게이트 + per-tool 매니페스트 확장).
- `versionCode` `260521003` → `260521004`.
- `server.yml` `server.version` `0.2.2` → `0.3.0`.

## [0.2.2] - 2026-05-21

> v0.2.0 deferred 항목 중 빌드 산출물 housekeeping 2건 (F-1, F-2) 처리.
> 사용자 가시 동작 변경 없음.

### Added
- **Server**: `ArtifactService.pruneOldArtifacts(projectId, keepCount)` —
  프로젝트당 newest-first로 정렬해 `keepCount` 초과분을 자동 삭제. 각 항목별로
  (1) artifact 디렉토리 통째 삭제 (APK + metadata.json,
  `ensureUnderWorkspace` 검증 후), (2) `Builds.artifactId` 참조 null로
  해제(build history는 보존), (3) `Artifacts` row delete. `keepCount <= 0`은
  "정리 안 함"으로 처리. 항목별 실패는 KotlinLogging WARN으로 격리.
- **Server**: `storeDebugApk` 직후 `pruneOldArtifacts(projectId,
  config.workspace.artifactKeepCount)` 자동 호출 (기본 20개 보관).
- **Repo**: `ArtifactRepository.listForProjectAll(projectId)` (limit 없음),
  `ArtifactRepository.delete(artifactId): Int`,
  `BuildRepository.detachArtifact(artifactId)` 신설.

### Changed
- **Build infra**: `gradle/wrapper/gradle-wrapper.jar`를 Gradle 9.5.1 정본
  배포본에서 ship한 wrapper로 재생성 (`./gradlew wrapper
  --gradle-version 9.5.1 --distribution-type bin`). jar 48966 → 48462 bytes.
  SHA-256 변경. `gradle-wrapper.properties`에 9.5.1 기본값 `retries=0` /
  `retryBackOffMs=500` 자동 추가. 분석 보고서 F-1 항목 해소.
- **Server**: `ArtifactService` 시그니처 확장 — 의존성에 `config: ServerConfig`,
  `buildRepo: BuildRepository` 추가. `ServerMain` 와이어링 동기.

### Versions
- `versionName` `0.2.1` → `0.2.2` (PATCH: 자동 정리/빌드 인프라).
- `versionCode` `260521002` → `260521003`.
- `server.yml` `server.version` `0.2.1` → `0.2.2`.

## [0.2.1] - 2026-05-21

> v0.2.0의 deferred 항목 중 deprecated 엔드포인트 제거. one-shot Claude task
> 파이프라인 잔재를 모두 정리하고 콘솔 단일 경로로 통합.

### Removed
- **Server**: `POST /api/projects/{id}/claude/tasks` (deprecated 핸들러),
  `GET /api/projects/{id}/claude/tasks`, `GET .../claude/tasks/{taskId}`,
  `POST .../claude/tasks/{taskId}/cancel` 4개 엔드포인트.
- **Server**: WebSocket `/ws/projects/{id}/tasks/{taskId}/logs` 엔드포인트
  (콘솔 WS 및 빌드 WS만 남김).
- **Server 파일**: `claude/ClaudeRoutes.kt`, `claude/ClaudeRunner.kt`,
  `claude/ClaudePromptBuilder.kt`, `tasks/TaskRoutes.kt`,
  `repo/TaskRepository.kt`.
- **DB**: `Tasks` 테이블 정의 삭제 (`db/Schemas.kt`). 신규 서버는 이 테이블을
  더 이상 생성하지 않음. 기존 DB 파일은 그대로 두면 됨 (테이블만 unused
  상태로 남음).
- **Shared**: `ClaudeTaskRequestDto`, `TaskDto`, `TaskType` 제거. `TaskStatus`
  enum은 BuildRow가 사용하므로 보존하되 KDoc에 build 전용임을 명시.
- **Shared**: `ApiPath.claudeTasks/claudeTask/claudeTaskCancel/wsTaskLogs`
  4개 path 상수 제거.
- **Android**: `ApiService.submitClaudeTask/listClaudeTasks/cancelTask` 함수
  제거. `Repositories.kt` `TaskRepository` 클래스 통째로 제거.
  `WsClient.streamTaskLogs` 제거.
- **Android Nav**: `Routes.LOG` (`projects/{id}/logs/{kind}/{taskId}`)
  → `Routes.BUILD_LOG` (`projects/{id}/builds/{buildId}/logs`)로 단순화.
  `ARG_KIND`/`ARG_TASK_ID` 제거, `ARG_BUILD_ID` 신설.
  `Routes.log(id, kind, taskId)` → `Routes.buildLog(id, buildId)`.

### Changed
- **Android `LogScreen`**: build-only로 단순화. ViewModel은 `WsClient` +
  `BuildRepository`만 주입받고 `kind` 분기 제거.
- **Server `StatusService`**: `taskRepo` → `buildRepo` 의존성으로 교체.
  `runningTaskCount`는 이제 `Builds` 테이블의 RUNNING+PENDING 개수.
  `BuildRepository.countRunning()` 메서드 신설.
- **Server `ServerContext`**: `taskRepo`, `claude: ClaudeRunner` 필드 제거.
- **Server `tasks/LogWriter.kt`**: KDoc에서 ClaudeRunner 언급 제거.

### Versions
- `versionName` `0.2.0` → `0.2.1` (PATCH: deprecated 코드 정리, 동작 변경
  없음).
- `versionCode` `260521001` → `260521002`.
- `server.yml` `server.version` `0.2.0` → `0.2.1`.

## [0.2.0] - 2026-05-21

> project-claude-console — 채팅형 Claude Console + 영속 세션 + 액션 레지스트리.
> PDCA 사이클 종료 Match Rate 98% (archived at `docs/archive/2026-05/project-claude-console/`).

### Added — Server (persistent Claude console)
- `claude/ClaudeSessionManager`: 프로젝트당 1개 영속 `claude --print --output-format stream-json --input-format stream-json [--resume <id>]` 자식 프로세스. stdin/stdout 파이프 + per-project stdin mutex + idle 30분 reaper + crash/resume-failure 감지.
- `claude/ClaudeStreamParser` + `claude/ClaudeEvent`: stdout 라인 → sealed `ClaudeEvent`(SessionStarted / AssistantMessage / ToolUse / ToolResult / Error / Done / Unknown).
- `claude/ConsoleRoutes`: `POST /api/projects/{id}/claude/console/prompt`, `POST .../console/new`, `GET .../claude/status`.
- `claude/ClaudeStatusService`: `claude /status` 60s 캐시 (slash command sidecar fallback 포함).
- `ws/LogHub` 확장: 콘솔 토픽 ring buffer 200건 + 단조 증가 seq + `?since=<seq>` replay.
- `ws/WsRoutes` 확장: `/ws/projects/{id}/console/logs?since=` 양방향 (client→server: auth/user_prompt/action_invoke, server→client: console_* sealed frames).
- `actions/` 패키지: sealed `ProjectAction` (SendPrompt / InvokeMcpTool / RunServerAction / OpenPalette / SnippetInsert / InvokeClaudeSlashCommand) + `ProjectActionRegistry` (resources + workspace `actions.user.json` 병합, MCP `.mcp.json` 자동 발견, mtime 10s 핫리로드) + `ServerActionHandler` (whitelist: `build.debug`, `git.{status,diff,log}`, slash `{status,cost,model,clear,memory,plan,compact}`) + routes (`GET /actions`, `POST /actions/invoke`, 4KB params cap).
- `resources/actions/`: 기본 manifest `build.json`, `git.json`, `claude.json`, `snippets.json`.
- `projects/KeystoreGenerator`: 프로젝트 등록 시 디버그/릴리즈 동일 키스토어 자동 생성.
- `error/StatusPagesPlugin`: 표준 `ApiErrorDto` 응답 코드 확장 (`action_not_allowed`, `claude_send_failed`, `params_too_large`, `prompt_too_large` 등).

### Added — Android (chat console)
- `ui/console/`: `ProjectConsoleScreen` (TopAppBar + LazyColumn 대화 + Surface 입력바), `ConsoleViewModel`, `messages/` 카드 6종 (AssistantBubble/ToolUse/ToolResult/Error/System/Unknown), `input/PromptInputBar` + `VoiceButton` (SpeechRecognizer 한/영) + `QuickActionChips` (카테고리 탭 + LazyRow), `scroll/AutoScrollState` (스크롤 잠금 + "↓ Jump to latest"), `status/StatusPanel` (collapsible).
- `data/remote/ConsoleWsClient` + `data/repository/ConsoleRepository`: WS 양방향 + `?since` 재접속 replay.
- `AndroidManifest.xml`: `RECORD_AUDIO` 권한 선언 + `<queries>` SpeechRecognizer.
- `strings.xml`: 콘솔 UI 약 30개 키 신규 (하드코딩 제거).

### Added — Shared
- `dto/ConsoleDtos.kt`: `PromptRequestDto`, `PromptAcceptedDto`, `ClaudeStatusDto`.
- `dto/ProjectActionDto.kt`: 액션 트리 wire DTO (sealed `ProjectActionDto` 6 변형 + `ActionCategoryDto` + `ActionTreeDto` + `ActionInvokeRequestDto`).
- `ws/WsFrame`: `Console*` 서브타입 10종 (`SessionStarted`/`Assistant`/`ToolUse`/`ToolResult`/`Error`/`Done`/`Unknown`/`System`/`ReplayBegin`/`ReplayEnd`) + client→server `UserPrompt`/`ActionInvoke`.
- `ApiPath`: console + actions 엔드포인트 상수.

### Changed
- `claude/ClaudeRoutes.POST /api/projects/{id}/claude/tasks` → **deprecated** (one-shot 모드, 1 사이클 호환 유지). 신규 클라이언트는 console 엔드포인트 사용.
- `WorkspacePath` + `PathSafety`: `.vibecoder` 메타 경로를 `<root>/.vibecoder/<projectId>/`로 통일 (이전: `<root>/<projectId>/.vibecoder/`).
- `server.yml`: `workspace.root` 기본값 `./vibe-coder-server-data/workspace` → `./workspace`; `security.restrictToWorkspace` 옵션 제거 (`PathSafety`가 항상 강제하므로 잉여).
- Repositories(6개): `Clock` 주입으로 결정성 향상; `ProjectService`는 키스토어 생성 흐름 통합.
- Android nav `Routes`: `ProjectDetail` 라우트가 `ProjectConsoleScreen`을 가리키도록 변경; `ClaudePrompt` 라우트는 console로 흡수.
- `ProjectRegisterScreen`: 키스토어 자동 생성 안내 + 폼 확장.
- `MainActivity`: 음성 권한 launcher + 콘솔 진입 흐름.

### Removed
- `ui/claude/ClaudePromptScreen.kt`, `ui/projects/ProjectDetailScreen.kt` (콘솔로 흡수). 라우트도 함께 제거.

### Versions
- `versionName` `0.1.0` → `0.2.0` (MINOR: 영속 콘솔/액션 레지스트리/음성 입력 — 하위호환 신규 기능, 사용자 워크플로 확장).
- `versionCode` `260517001` → `260521001` (yymmddrrr).
- `server.yml` `server.version` `0.1.0` → `0.2.0` (Plan 문서와 동기).

### Deferred (다음 사이클 후보)
- `ProjectActionDto.requires` 권한 게이트 + Android 비활성 chip + 사유 tooltip (FR-11-b).
- MCP per-tool enumeration (현재 per-server 1 chip만; JSON Schema 기반 폼 후속).
- Deprecated `/api/projects/{id}/claude/tasks` 제거 (1 사이클 유예 후).
- `gradle-wrapper.jar` 바이너리 생성 (`gradle wrapper --gradle-version 9.5.1`).
- `artifactKeepCount` 자동 정리 (현재 수동 DELETE artifact API만).

## [0.1.0] - 2026-05-17

### Added
- Initial monorepo skeleton: `:shared`, `:server`, `:android-app:app`.
- Gradle 9.5.1 wrapper + version catalog (`gradle/libs.versions.toml`).
- Build matrix per global `CLAUDE.md` §2-2-1: Gradle 9.5.1 / AGP 9.2.0 / Kotlin 2.2.20 / Compose BOM 2026.05.00 / Hilt 2.59.2 / JDK 21.
- PDCA Plan and Design documents under `docs/01-plan/` and `docs/02-design/`.
- `shared` module: 13 `@Serializable` DTOs, API path constants, WebSocket frame sealed class.
- `server` module: Ktor 3.x + Exposed + SQLite + YAML config + pairing-code auth + WebSocket log streaming + Claude/Gradle/Git process execution + APK artifact management with SHA-256.
- `android-app` module: Jetpack Compose + Material 3 + Hilt + Ktor Client + DataStore + 12 screens (Connect/Dashboard/Environment/ProjectList/Register/Detail/ClaudePrompt/Log/Build/Artifact/Git/Files) + APK installer via FileProvider.

### Notes
- `android.disallowKotlinSourceSets=false` is required for AGP 9 + KSP2 (workaround until KSP migrates to `android.sourceSets`).
- `-Xannotation-default-target=param-property` is applied to Android module (Kotlin 2.2 KT-73255 forward-compat for Hilt).

### Changed during first build (2026-05-17)

Adjustments made while producing the first runnable debug APK:

- **Compose Compiler plugin**: Kotlin 2.0+ moved Compose Compiler into a separate Gradle plugin. Added `org.jetbrains.kotlin.plugin.compose` (alias `libs.plugins.kotlin.compose`) to root `build.gradle.kts` and to `android-app/app/build.gradle.kts`.
- **JDK toolchain 21 → 17**: Local environment ships JDK 17 only; Foojay auto-download did not transparently honour `jvmToolchain(21)` for the AGP `hiltJavaCompileDebug` task. Downgraded `jvmToolchain` and `sourceCompatibility` / `targetCompatibility` in `shared/`, `server/`, and `android-app/app/` to 17. AGP 9 + Kotlin 2.2 remain fully supported on JDK 17.
- **Ktor 3.1.2 API alignment**:
  - `KtorClient.kt` — `sendWithoutRequest` block now reads the URL via `request.url.pathSegments` (URLBuilder `encodedPath` was not resolvable in the lambda's inference scope).
  - `DownloadService.kt` — replaced `ByteReadChannel.readAvailable` (whose ext-fn location moved between Ktor minors) with the stable `bodyAsChannel().toInputStream()` JVM helper.
  - `WsClient.kt` — added explicit `io.ktor.websocket.close` import for `DefaultClientWebSocketSession.close()`.
- **Foojay toolchain resolver**: Added `org.gradle.toolchains.foojay-resolver-convention 0.10.0` to `settings.gradle.kts` and `org.gradle.java.installations.auto-download=true` to `gradle.properties` so a future move back to JDK 21 will auto-provision the toolchain.
- **First APK**: `android-app/app/build/outputs/apk/debug/app-debug.apk` (~21 MB, versionCode `260517001`, versionName `0.1.0`, applicationId `com.siamakerlab.vibecoder.console.debug`).
