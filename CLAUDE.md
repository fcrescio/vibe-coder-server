# vibe-coder-server 프로젝트 규칙

> 전역 룰(`~/.claude/CLAUDE.md`)을 기본으로 따른다. 이 문서는
> **vibe-coder-server 리포에만 적용되는 예외·보완 사항**만 명시한다.

## 1. 프로젝트 성격

- **단일 사용자, LAN 페어링 모드의 서버 컴포넌트.**
  본인 PC에서 도커 컨테이너로 실행되며, 같은 LAN 안의 본인 Android 단말
  1대가 `vibe-coder-android` (별도 리포) 로 접속한다.
- **다중 사용자 / 공개 배포 / 스토어 출시 대상이 아님.**
- 모듈 구성:
  - `:server` — Ktor 백엔드. Claude Code / Gradle / Git 자식 프로세스 관리,
    SQLite(Exposed) 저장소, WebSocket 로그 허브, Admin 웹.
  - `:shared` — JVM-only DTO / `ApiPath` / `WsFrame`.
    같은 코드가 `vibe-coder-android` 리포의 `:shared` 모듈에도
    **동일한 사본**으로 존재한다 (양쪽 수동 동기화).

## 2. 짝 리포 관계

- Android 리포: `vibe-coder-android` (`D:\dev\vibe-coder-android`)
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
  `docker/README.md` 의 `siamakerlab/vibe-coder:<version>` 을 동기.
- 키스토어는 본 리포 외부에서 관리 (서버는 keystore 사용 안 함).

## 5. 문서 / PDCA

- 모든 문서는 한국어.
- PDCA 사이클 결과물은 본 리포의 `docs/` 하위 (서버 리포 한정).
- bkit 도구 상태(`.bkit/`)는 커밋하지 않는다 (`.gitignore`).

## 6. 실행 환경

- JDK toolchain 17 (전역 매트릭스 21 → 로컬 환경 제약으로 17, Ktor 3.x
  호환).
- 워크스페이스 기본값: `./workspace` (CLI `--workspace <path>` 로 override).
- 도커 실행 시 `VIBECODER_WORKSPACE_ROOT=/workspace` 가 자동 적용.

## 7. 알려진 베이스라인 결함

분리 시점(v0.4.0 main)에 **다음이 깨진 상태로 확인됨**. 본 리포의
첫 작업으로 정리 필요:

- `ApkFinder.kt:7` 의 `import kotlin.streams.toList` — Kotlin 2.2 에서
  제거된 API. `Stream.toList()` (JDK 16+) 또는 `Collectors.toList()` 로
  대체 필요. 이 import 가 깨지면서 ApkFinder 파일 전체 파싱이 어긋나고,
  `findLatestDebug` 참조도 끊겨 `BuildService` 도 연쇄 실패.
- `ServerActionHandler.kt:55` 의 `submitDebug` 미해결 참조.
- `BuildService.kt:109` 의 타입 추론 실패 (위 두 원인의 파급).

v0.4.0 commit 메시지에는 `:server:installDist` 통과로 적혀 있으나
현재 main 체크아웃에선 재현되지 않는다 — 별도 PR 로 회수 예정.
