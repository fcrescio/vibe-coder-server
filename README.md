# Vibe Coder — Server

> LAN 너머의 Android 단말(`vibe-coder-android`, 별도 리포)이 원격 조종하는
> PC-side Ktor 서버. Claude Code CLI / Gradle Wrapper / Git CLI / 파일
> 관리 자식 프로세스를 모두 이 서버가 소유하고, 로그는 WebSocket 으로
> 클라이언트에 스트림한다.

Vibe Coder 는 "Android 위에서 개발하기" 가 아니라 **원격 콘솔**입니다.
서버 PC 가 무거운 작업을 처리하고, Android 앱은 프롬프트 전송 · 로그
시청 · 산출물 다운로드만 합니다.

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
>>> Server URL : http://192.168.0.10:17880
>>> Admin URL  : http://192.168.0.10:17880/admin
```

브라우저로 `Admin URL` 에 접속해 첫 admin 계정을 설정한 뒤, Android
앱(`vibe-coder-android`) 에서 같은 username/password 로 로그인합니다.

### Docker 실행

```bash
docker pull siamakerlab/vibe-coder-server:0.4.1
cd ~/vibe-coder && cp docker/compose.yml . && cp docker/.env.example .env
# .env 편집 후
docker compose up -d
docker exec -it vibe-coder vibe-doctor   # Android SDK + Claude 설치
```

자세한 가이드는 `docker/README.md` 를 참고하세요.

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
