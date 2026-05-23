# vibe-coder-server 프로젝트 규칙

> 전역 룰(`~/.claude/CLAUDE.md`)을 기본으로 따른다. 이 문서는
> **vibe-coder-server 리포에만 적용되는 예외·보완 사항**만 명시한다.

## 1. 프로젝트 성격

- **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만드는
  "외부 접근 가능한 개발머신" 그 자체. 브라우저만 있으면 별도 클라이언트
  없이도 프로젝트 생성 · 프롬프트 전송 · 빌드 · APK 다운로드까지 완결.
- **단일 사용자.** 본인 PC 의 도커 컨테이너로 실행되며 LAN 내부 / 또는
  본인이 책임지는 외부 노출 경로(SSH 터널 · reverse proxy 등) 로 접근.
  공개 배포 / 스토어 출시 / 멀티테넌트 대상은 아님.
- **안드로이드 앱은 부가 클라이언트.** `vibe-coder-android` (별도 리포
  — §2 참고) 는 같은 서버를 부르는 모바일 컨소시엄 같은 존재. 없어도
  웹 만으로 모든 기능을 사용할 수 있어야 함 — 이 원칙이 깨지면 회귀로
  간주.
- 모듈 구성:
  - `:server` — Ktor 백엔드. Claude Code / Gradle / Git 자식 프로세스 관리,
    SQLite(Exposed) 저장소, WebSocket 로그 허브, SSR 웹 UI (대시보드 /
    프로젝트 / 콘솔 / 빌드 / 설정 / 디바이스, v0.5.0+).
  - `:shared` — JVM-only DTO / `ApiPath` / `WsFrame`.
    같은 코드가 `vibe-coder-android` 리포의 `:shared` 모듈에도
    **동일한 사본**으로 존재한다 (양쪽 수동 동기화).

## 2. 짝 리포 관계

- Android 리포: `vibe-coder-android`
  - 원격: `ssh://git@gitea.wody.kr:2929/wody/vibe-coder-android.git`
  - 로컬 체크아웃 (참조용): `D:\dev\vibe-coder-android`
- 두 리포는 `:shared` 모듈을 통해 wire-level 호환을 유지한다.
  `ApiPath` 상수 / DTO 직렬화 / `WsFrame` 변경 시 양쪽 모두 같은 값으로
  업데이트해야 한다. 호환 깨짐을 발견하면 CHANGELOG 에 "**Wire change**"
  로 명시.

## 3. 보안 / 워크스페이스

- 서버는 워크스페이스(`workspace.root`) **외부 경로 접근 절대 금지**.
  모든 디스크 touch 는 `WorkspacePath` + `PathSafety` 를 경유한다.
- 인증: v0.4.0 이후 username/password 통합 인증 (페어링 코드 deprecated).
  비밀번호는 BCrypt cost 12 hash 만 DB에 저장. 10회 실패 시 15분 잠금.
- raw shell UI · 사용자 입력 shell 실행 절대 금지.
- 업로드 확장자 블랙리스트(`exe/bat/cmd/ps1/sh`)는 `server.yml` 에서 관리.
- 토큰: `Authorization: Bearer …` 헤더 / `vibe_session` 쿠키 양쪽으로
  유효. 같은 토큰이 두 경로 어느 쪽으로 와도 인증된다.

## 4. 버전 / 릴리즈

- `versionName` / `versionCode` 는 `server/src/main/resources/config/server.yml`
  의 `server.version` 에만 존재한다 (Android 리포의 `build.gradle.kts` 와
  **독립 버전**).
- Docker 이미지 태그는 `server.version` 과 같은 값을 사용한다.
  `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의
  `siamakerlab/vibe-coder-server:<version>` 을 동기.

## 5. 문서 / PDCA

- 모든 문서는 한국어.
- PDCA 사이클 결과물은 본 리포의 `docs/` 하위 (서버 리포 한정).
- bkit 도구 상태(`.bkit/`)는 커밋하지 않는다 (`.gitignore`).

## 6. 실행 환경

- JDK toolchain 17 (전역 매트릭스 21 → 로컬 환경 제약으로 17, Ktor 3.x
  호환).
- 워크스페이스 기본값: `./workspace` (CLI `--workspace <path>` 로 override).
- 도커 실행 시 `VIBECODER_WORKSPACE_ROOT=/workspace` 가 자동 적용.

## 7. 베이스라인 결함 회수 이력 (v0.4.1)

분리 시점(v0.4.0 main)에 보고됐던 빌드 결함 + 보조 결함은 v0.4.1 에서
일괄 회수. 자세한 회수 내역은 `CHANGELOG.md` 의 v0.4.1 섹션을 참고.

회수된 결함 요약:

- `ApkFinder.kt` — Kotlin 2.2 에서 제거된 `kotlin.streams.toList` import.
  추가로 KDoc 본문의 `build/outputs/apk/debug/*.apk` 표현 안에 `/*`
  시퀀스가 nested comment 시작으로 해석되어 `Unclosed comment` 신택스
  에러를 일으키던 문제도 같은 PR 에서 KDoc 재작성으로 회피.
  처음에는 import 결함이 가려져 있어 KDoc 결함이 드러나지 않았다.
- `ServerActionHandler.kt:55` — 존재하지 않는 `builds.submitDebug` 호출을
  실제 메소드 `builds.enqueueDebug` 로 정정.
- `BuildService.kt` — 위 두 원인의 파급. root cause 해결과 동시에 해소.
- `auth/AuthPlugin.kt` — Ktor 3.x `Principal` interface deprecated 경고.
  `DevicePrincipal` 상위 인터페이스 제거.
- `files/FileRoutes.kt` — `PartData.FileItem.streamProvider` deprecated.
  `provider().toInputStream()` 로 마이그.
- **`.gitignore` 패턴 결함** — `build/` 가 너무 광범위해서 source 패키지
  디렉토리 `server/src/main/kotlin/.../server/build/` 까지 무시되고
  있었다 (4개 핵심 파일 untracked). `**/build/` + `!**/src/**/build/**`
  로 패턴을 좁히고 누락 파일을 정상 등록.

v0.4.0 commit 메시지에 `:server:installDist` 통과로 적혀 있던 것은 Gradle
daemon 캐시 효과로 보이며, `./gradlew clean` 후엔 모두 재현됐다. 향후
"통과 확인" 은 반드시 clean 빌드로 한다.
