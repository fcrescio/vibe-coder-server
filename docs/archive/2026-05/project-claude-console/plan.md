# project-claude-console Planning Document

> **Summary**: 안드로이드 프로젝트 상세 화면을 채팅형 Claude Console로 재설계하고, 서버가 프로젝트당 1개의 영속 `claude --print --output-format stream-json` 프로세스를 유지하여 모바일에서도 로컬 Claude Code에 가까운 연속 세션 작업을 가능하게 한다.
>
> **Project**: vibe-coder
> **Version**: 0.2.0
> **Author**: wody
> **Date**: 2026-05-18
> **Status**: Draft

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 현재 프롬프트 입력 화면과 로그 화면이 분리되어 대화 흐름이 끊기고, 매 프롬프트마다 새 `claude` 프로세스가 떠서 세션 컨텍스트가 유지되지 않는다. 모바일에서 입력 부담도 큼. |
| **Solution** | (1) `ProjectDetailScreen`을 상단 대화 스트림 + 하단 프롬프트 입력의 채팅형 콘솔로 재설계 (2) 서버가 프로젝트당 1개 영속 `claude --print --output-format stream-json --input-format stream-json --resume <id>` 프로세스 운영 (3) 안드로이드 SpeechRecognizer 음성 입력 + 모듈화된 퀵 액션 칩 (4) WebSocket 재접속 시 서버측 ring buffer로 이벤트 손실 방지 |
| **Function/UX Effect** | 폰 한 손으로도 "프로젝트 → 프롬프트 입력/음성 → Claude 작업 결과 실시간 확인 → 추가 지시 → 빌드" 흐름이 끊기지 않음. 로컬 CC 대비 모바일에 재해석된 conversational 경험. |
| **Core Value** | 폰만 가진 상황에서도 PC의 Claude Code를 "원격조종"하여 연속적 vibe-coding 사이클을 닫는다. |

---

## Context Anchor

> Auto-generated from Executive Summary. Propagated to Design/Do documents for context continuity.

| Key | Value |
|-----|-------|
| **WHY** | 현 분리된 화면 + 세션 무유지 구조로는 모바일에서 Claude Code의 연속 대화 경험을 재현 불가 |
| **WHO** | vibe-coder 단일 사용자 (sia@siamakerlab.com) — PC 서버 + 안드로이드 클라이언트 LAN 페어 |
| **RISK** | (1) Claude CLI stream-json 포맷 변동 (2) 프로세스 leak / crash 복구 (3) 동시 다중 디바이스 충돌 (4) WebSocket 끊김 시 이벤트 손실 |
| **SUCCESS** | 한 세션 안에서 5회 이상 연속 프롬프트 → Claude가 이전 컨텍스트 유지하여 응답 / 재접속 후 직전 200건 이벤트 복원 / "Start new session" 후 새 session-id 발급 확인 |
| **SCOPE** | Phase A: 서버 세션 매니저 + WS 양방향 + JSON 파서. Phase B: 안드로이드 콘솔 화면 + 음성/칩. Phase C: 재접속 버퍼 + 크래시 복구 |

---

## 1. Overview

### 1.1 Purpose

안드로이드 vibe-coder 앱의 **프로젝트 상세 화면(현 `ProjectDetailScreen`)을 채팅형 Claude Console로 전면 재설계**하고, 서버가 **프로젝트당 1개의 영속 `claude` 프로세스를 유지**하여 한 세션 안에서 연속적인 vibe-coding 작업이 가능하게 한다.

### 1.2 Background

직전 대화에서 발견된 문제:
- 사용자가 프로젝트 등록 후 작업 시 "프롬프트 입력 → 별도 로그 화면 이동 → 결과 확인 → 다시 프롬프트 화면" 의 단절된 흐름을 거침
- `ClaudeRunner.execute`는 매 호출마다 `claude -p "<wrapped>"` one-shot 프로세스를 띄움 → Claude의 conversation memory가 호출 사이에 유지되지 않음
- "로컬 Claude Code를 핸드폰으로 원격조종"이라는 vibe-coder MVP의 핵심 가치(WHY)가 현 구조로는 부분적으로만 달성됨

직전 사용자 결정:
- 권장 구현 방식: `claude --print --output-format stream-json --input-format stream-json --resume <session-id>` 영속 프로세스 1개/프로젝트
- 음성 입력: 안드로이드 기본 `SpeechRecognizer`
- 재접속 보호: 서버측 ring buffer (최근 N건)
- 퀵 액션: 각 칩을 모듈로 분리, 향후 추가/편집 용이한 구조

### 1.3 Related Documents

- Parent MVP Plan: [`vibe-coder-mvp.plan.md`](./vibe-coder-mvp.plan.md)
- Parent Design: [`../02-design/features/vibe-coder-mvp.design.md`](../../02-design/features/vibe-coder-mvp.design.md)
- 전역 룰: `/home/wody/.claude/CLAUDE.md` (Compose/Material3/Hilt/MVVM/Lucide 등)
- Claude CLI 매뉴얼: https://docs.anthropic.com/claude-code/ — `--print`, `--output-format stream-json`, `--input-format stream-json`, `--resume`, `--continue` 옵션 명세

---

## 2. Scope

### 2.1 In Scope

- [ ] **A. 서버 세션 매니저 (`ClaudeSessionManager`)**: 프로젝트별 1개 `claude --print --output-format stream-json --input-format stream-json [--resume <id>]` 프로세스 lifecycle 관리. stdin 주입, stdout 라인 파싱, session-id 캡처.
- [ ] **B. 세션 상태 영속화**: session-id를 `<workspace>/.vibecoder/<projectId>/claude-session.id`에 저장. 프로세스 재기동 시 `--resume` 으로 이어 받기.
- [ ] **C. WebSocket 양방향 채널**: 클라이언트→서버는 사용자 프롬프트, 서버→클라이언트는 assistant 메시지/tool_use/tool_result 등 stream-json 이벤트.
- [ ] **D. WS 이벤트 ring buffer**: 프로젝트별 최근 N건(기본 200) in-memory 보관. 재접속 시 마지막 수신 cursor 이후 이벤트 replay.
- [ ] **E. "Start new session" 액션**: 현 프로세스 SIGTERM + session-id 파일 + ring buffer 삭제. 다음 프롬프트가 새 세션 시작.
- [ ] **F. 안드로이드 `ProjectConsoleScreen`**: 상단 대화 히스토리(스크롤) + 하단 프롬프트 입력 + 음성/Send/New session 액션. 현 `ProjectDetailScreen` + `ClaudePromptScreen` + `LogScreen` 통합.
- [ ] **G. 안드로이드 메시지 렌더링**: stream-json event type 별로 다른 Composable. AssistantText, ToolUse 카드, ToolResult, FileEdit diff preview 등.
- [ ] **H. 음성 입력**: `android.speech.SpeechRecognizer`. RECORD_AUDIO 런타임 권한. 한국어/영어 자동.
- [ ] **I. 확장 가능한 액션 플러그인 시스템 (Action Registry)**: 단순 프롬프트 칩이 아닌 **다형성 액션 레지스트리**. 액션 타입 sealed class:
  - `SendPromptAction` — 정적/매개변수화된 프롬프트를 Claude 세션에 송신 (예: 빌드, 테스트, 린트, "이 파일 리뷰해줘 — {filePath}")
  - `InvokeMcpToolAction` — 등록된 MCP 서버의 특정 tool을 직접 호출(Claude 경유 없이) (예: `bkit_pdca_status`, `gitea_list_issues`)
  - `RunServerActionAction` — 서버에 정의된 named 액션 실행 (예: `build:debug`, `git:status`, `files:list`)
  - `OpenPaletteAction` — 하위 액션 그룹/팔레트 열기 (계층화)
  - `SnippetInsertAction` — 입력박스에 텍스트 삽입만 (예: 자주 쓰는 사용자 프롬프트 템플릿)
  - `InvokeClaudeSlashCommandAction` — Claude Code 슬래시 커맨드 호출 (`/status`, `/cost`, `/model`, `/clear`, `/memory`, `/plan`, ...). 현 사이클은 **아키텍처만 준비**, 구체 커맨드별 구현/UX는 후속 TODO.
  
  레지스트리 구조: **서버측 JSON manifest**(`server/src/main/resources/actions/*.json`) 자동 로드 + **사용자 정의**(`<workspace>/.vibecoder/actions.user.json`) 오버라이드/추가. 카테고리(Build/Git/MCP/Snippets/Custom)로 그룹핑. 안드로이드는 `GET /api/projects/{id}/actions` 한 번 호출로 전체 트리 받음.
  
  기본 액션(MVP에서 함께 출시): build/test/lint/status/commit/lastError-explain 6개 + MCP 도구 자동발견(서버 `.mcp.json`의 tool 목록을 액션으로 동적 노출). 향후 추가는 매니페스트 파일만 작성/편집.
- [ ] **J. 스트리밍 중 자동 스크롤 잠금**: 사용자가 위로 스크롤하면 새 이벤트 도착 시 자동 따라가기 중지, 하단 "↓ Jump to latest" 버튼 노출.
- [ ] **K. 프로세스 crash 자동 복구**: 영속 프로세스가 비정상 종료하면 다음 프롬프트 송신 시 `--resume <id>`로 재기동.
- [ ] **L. Idle timeout**: 30분간 프롬프트 없으면 프로세스를 우아하게 종료(`--resume`으로 언제든 재개 가능하므로).
- [ ] **M. Claude `/status` 패널 (사용량/플랜 표시)**: 콘솔 상단 collapsible 영역에 현 Claude 플랜·잔여 사용량·세션 정보 표시. 우측 새로고침 아이콘. 서버 `GET /api/projects/{id}/claude/status` 엔드포인트가 `claude /status` 또는 동등 수단으로 정보 수집·캐시(60초 TTL). 본 사이클에 **UI + 엔드포인트 골격만** 구현. 정확한 stdout 파싱·필드 매핑은 Claude CLI 출력 형식 확인 후 후속 TODO.
- [ ] **N. Claude 슬래시 커맨드 라우팅 인프라**: stdin 주입 시 입력이 `/`로 시작하면 `InvokeClaudeSlashCommandAction` 경로로 분기. stream-json input이 슬래시 커맨드를 직접 받지 못하는 경우 **사이드채널 one-shot 호출**(`claude /<cmd>`)로 fallback하고 결과를 콘솔에 시스템 알림 카드로 표시. 본 사이클에 **분기 인프라 + `/status` 1개 데모**만, 다른 커맨드 구현은 후속 TODO.

### 2.2 Out of Scope

- 이미지/파일 드래그&드롭 입력 (안드로이드 갤러리/파일 선택 UI 필요)
- 같은 프로젝트에 대한 동시 다중 디바이스 멀티 세션 (현재는 1 프로젝트=1 세션, 다중 디바이스는 같은 세션 broadcast)
- MCP 서버 동적 설정 UI
- Claude Plan mode 토글 UI
- 세션 히스토리 사이드바 (직전 세션 목록·검색)
- **슬래시 커맨드별 전용 UI**: `/clear`, `/memory`, `/cost`, `/model`, `/plan` 등의 정밀한 출력 파싱·정형화 UI는 후속 TODO. 단 **`/status`만 1개 데모로 포함** (사용자 우선 요청). 다른 커맨드들은 `InvokeClaudeSlashCommandAction` 라우팅 인프라가 있으므로 후속 사이클에서 manifest 추가만으로 노출 가능.
- 응답 도중 사용자가 Stop 누르는 기능 (개선 사이클에서 추가)
- 사용자 정의 액션을 안드로이드 앱 내에서 GUI로 작성/편집하는 기능 (workspace `actions.user.json` 직접 편집은 가능)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 안드로이드 `ProjectDetailScreen`을 채팅형 `ProjectConsoleScreen`으로 교체. 상단 대화 영역, 하단 프롬프트 입력, IME 띄울 시 자동 패딩. | High | Pending |
| FR-02 | 서버는 프로젝트당 1개의 `claude --print --output-format stream-json --input-format stream-json [--resume <id>]` 자식 프로세스를 유지. stdin/stdout/stderr 파이프 연결. | High | Pending |
| FR-03 | 사용자 프롬프트는 WebSocket으로 서버에 전달되어 자식 프로세스의 stdin에 한 줄 JSON으로 주입. | High | Pending |
| FR-04 | Claude의 stdout은 라인별 JSON 이벤트로 파싱되어 WebSocket으로 안드로이드에 중계. event type 5+ 종(assistant_message, tool_use, tool_result, system_init/session, error). | High | Pending |
| FR-05 | session-id는 첫 stream-json 이벤트(`system` 또는 동등 타입)에서 캡처하여 `<workspace>/.vibecoder/<projectId>/claude-session.id`에 평문으로 저장. | High | Pending |
| FR-06 | 서버 재기동/프로세스 crash 후 첫 프롬프트 시 저장된 session-id가 있으면 `--resume <id>`로 시작. | High | Pending |
| FR-07 | "Start new session" 버튼/메뉴 → 현 프로세스 SIGTERM 후 5s 내 미종료 시 SIGKILL + session-id 파일 + ring buffer 삭제. 다음 프롬프트가 새 세션 시작. | High | Pending |
| FR-08 | 서버는 프로젝트별 최근 200건 WS 이벤트를 in-memory ring buffer에 보관. 각 이벤트에 단조증가 seq 부여. 재접속 시 `?since=<seq>` 파라미터로 누락분 replay. | High | Pending |
| FR-09 | 안드로이드는 stream-json event type별로 별도 Composable로 렌더 (assistant=텍스트 버블, tool_use=카드, tool_result=접힌 카드, error=경고 배너 등). 자유 텍스트 fallback도 지원. | High | Pending |
| FR-10 | 안드로이드 음성 입력: 마이크 버튼 → `SpeechRecognizer` 시작 → 인식 결과를 입력박스에 채움 → Send 자동 누르지 않음(사용자 확인 후 송신). RECORD_AUDIO 권한 미부여 시 안내 다이얼로그. | High | Pending |
| FR-11 | **확장 가능한 액션 플러그인 레지스트리**: 액션은 `sealed class ProjectAction` 다형 타입(SendPrompt/InvokeMcpTool/RunServerAction/OpenPalette/SnippetInsert). 서버 manifest(resources + workspace user override) → `GET /api/projects/{id}/actions` 트리 응답 (카테고리 그룹핑). 기본 액션 6개 + MCP 도구 자동 등록(서버 `.mcp.json`의 tool들 ↔ `InvokeMcpToolAction`으로 자동 매핑). 신규 액션 추가는 manifest 파일 1개 추가만으로 가능, 코드 변경/앱 재배포 불필요. | High | Pending |
| FR-11-a | manifest 핫리로드: workspace의 `actions.user.json` 변경 시 다음 액션 트리 요청부터 반영(파일 mtime 감지). | Medium | Pending |
| FR-11-b | 액션 권한 모델: 각 액션의 `requires` 필드(`claude_session`, `mcp:<server>`, `git`, `build` 등)로 사전 가용성 체크. 안드로이드는 비가용 액션을 disabled로 표시 + 사유 툴팁. | Medium | Pending |
| FR-12 | 안드로이드 스트리밍 중 사용자가 위로 스크롤 (마지막 메시지 이탈) 감지 시 자동 스크롤 잠금. 하단에 "↓ N new messages" 버튼 표시. 버튼 탭 시 잠금 해제 + 최하단 이동. | Medium | Pending |
| FR-13 | 자식 프로세스가 비정상 종료(exit code != 0 + 마지막 이벤트가 `error` 또는 stderr 출력)되면 서버는 ring buffer에 `process_terminated` 이벤트를 push. 다음 프롬프트가 들어오면 `--resume`로 재기동. | Medium | Pending |
| FR-14 | 30분간 새 프롬프트 없으면 자식 프로세스를 우아하게 종료. session-id는 보존하여 이후 프롬프트 시 `--resume`로 복귀. | Medium | Pending |
| FR-15 | 안드로이드 `Routes`에서 `ProjectDetail` 라우트를 새 `ProjectConsoleScreen`으로 교체. 기존 `ClaudePromptScreen`, `LogScreen` 라우트 제거. | Medium | Pending |
| FR-16 | 안드로이드 콘솔 화면 상단 액션바: 프로젝트 이름, 더보기 메뉴(빌드 실행, Git 상태, 파일, 아티팩트, **Start new session**, 프로젝트 삭제). | Medium | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| **Latency (first token)** | 사용자 Send 누른 시점부터 첫 WS 이벤트 도착까지 < 2초 (Claude 자체 latency 제외 시 < 200ms 서버 오버헤드) | curl + chrono 측정 |
| **Throughput** | stream-json 이벤트 100건/초 처리 시 backpressure 없이 안드로이드 전달 | LogHub 부하 시뮬레이션 |
| **Memory** | 프로젝트 1개 영속 세션 + 200건 ring buffer 메모리 사용량 < 50MB (Java heap) | `jcmd GC.heap_info` |
| **Reliability** | 프로세스 crash 후 다음 프롬프트로 자동 복구 (사용자 개입 없음) | mock kill -9 후 prompt 재시도 |
| **Security** | session-id 평문 저장 가능 (디바이스 토큰과 달리 Claude 내부 식별자). 단 워크스페이스 외부로 유출 금지 (`PathSafety`로 강제) | path safety 테스트 |
| **Mobile UX** | IME가 떴을 때 입력박스가 키보드에 가리지 않음 (imePadding) / 한손 조작 가능 | 실 디바이스 수동 검증 |
| **Accessibility** | 모든 버튼 contentDescription. 음성/Send/New session 음성 호버 가능. WCAG 2.1 AA color contrast. | Android Accessibility Scanner |

---

## 4. Success Criteria

### 4.1 Definition of Done

- [ ] FR-01~16 모두 구현 완료
- [ ] 한 세션 내 연속 5회 프롬프트 시나리오 통과 (Claude가 이전 컨텍스트 기억함을 응답 내용으로 확인)
- [ ] WS 강제 끊김 → 재접속 → 마지막 seq 이후 이벤트 replay 검증 (수동: 안드로이드 비행기 모드 toggle)
- [ ] 자식 프로세스 SIGKILL → 다음 프롬프트가 새 PID로 `--resume` 후 답변 정상 수신
- [ ] "Start new session" 클릭 → 새 session-id 발급 + 직전 세션 컨텍스트 미상속 확인
- [ ] 음성 입력으로 프롬프트 1회 이상 송신 성공 (한국어/영어 각 1회)
- [ ] 5개 기본 퀵 칩 전부 동작 (build/test/status/commit/lint)
- [ ] 스크롤 잠금 동작 검증
- [ ] 기존 `ClaudePromptScreen`/`LogScreen` 잔존 코드 제거, Routes 갱신
- [ ] 코드 리뷰 / 컨벤션 위반 0건

### 4.2 Quality Criteria

- [ ] Kotlin lint 위반 0 (`./gradlew :server:check :android-app:app:lintDebug`)
- [ ] `:server:compileKotlin` BUILD SUCCESSFUL warnings deprecation 외 0
- [ ] WS frame 직렬화/역직렬화 단위 테스트 통과 (kotlinx-serialization)
- [ ] `ClaudeSessionManager` 핵심 lifecycle 단위 테스트 (start/inject/terminate/resume) 통과
- [ ] gap-detector Match Rate ≥ 90%

---

## 5. Risks and Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **R-01** Claude CLI의 stream-json 포맷이 향후 버전에서 비호환 변경 | High | Medium | (1) `claude --version` 검사 후 호환 범위 외시 warning 로그 (2) JSON 파서를 sealed class + `JsonElement` fallback으로 작성하여 미지의 type도 raw로 안드로이드에 전달 |
| **R-02** 자식 프로세스 leak (서버 재기동/예외 시 좀비) | Medium | Medium | (1) 서버 shutdown hook에서 모든 자식 SIGTERM (2) 30분 idle timeout (3) 부팅 시 stale session-id가 가리키는 프로세스 없으면 그대로 사용, 있다면 destroy 후 새로 시작 |
| **R-03** 같은 프로젝트에 2개 디바이스 연결 시 stdin 경쟁 | Medium | Low | 서버측에서 프롬프트 inject를 mutex로 직렬화. 두 디바이스는 같은 stream을 broadcast 받음. UX 충돌은 명세상 허용 (사용자 동일인) |
| **R-04** WebSocket 끊김 + 200건 초과 누락 → 일부 이벤트 영구 손실 | Medium | Low | (1) ring buffer 크기 config로 조정 가능 (기본 200, 최대 2000) (2) 디스크 영속 옵션은 v2 사이클로 미룸 (3) 큰 응답은 1개 이벤트(전체 메시지)로 도착하므로 200건 = 200 메시지 ≈ 충분 |
| **R-05** RECORD_AUDIO 권한 거부 시 UX 단절 | Low | Medium | 거부 시 마이크 버튼은 비활성 + "권한 설정" 다이얼로그 안내. 키보드만으로도 100% 사용 가능 |
| **R-06** stream-json의 일부 이벤트가 매우 클 때 WS frame size 초과 | Low | Low | Ktor WebSockets `maxFrameSize = Long.MAX_VALUE` 이미 설정됨. 안드로이드 클라이언트 side만 점검 |
| **R-07** session-id 파일에 잘못된 값(이전 세션이 Claude쪽에서 만료) → `--resume` 실패 | Medium | Medium | resume 실패 감지(exit code + stderr 패턴) 시 session-id 파일 삭제 후 새 세션으로 재시도. 사용자에게 "Resumed failed, started new session" 정보 이벤트 전달 |
| **R-08** 모듈화 퀵 액션이 늘어나면서 화면이 가로로 넘침 | Low | Medium | LazyRow + 좌우 스와이프. order 필드로 우선순위 정렬. 사용자 정의 칩 hide/show 토글은 v2 사이클 |

---

## 6. Impact Analysis

### 6.1 Changed Resources

| Resource | Type | Change Description |
|----------|------|--------------------|
| `ClaudeRunner` (server) | Service | one-shot 실행 로직은 유지하되 새 `ClaudeSessionManager`로 영속 모드 분리. ClaudeRoutes에서 호출 경로 변경 |
| `ClaudeRoutes` (server) | API | `POST /api/projects/{id}/claude/tasks` 의 동작 변경: queue 대신 세션 매니저로 위임 (또는 새 엔드포인트 신설) |
| `WsFrame` (shared) | DTO | 신규 frame types 다수 추가 (`UserPrompt`, `Assistant`, `ToolUse`, `ToolResult`, `SessionStarted`, `ProcessTerminated`, `Replay` 등) |
| `WsRoutes` (server) | WS Routes | `/ws/projects/{id}/console/logs` 신규 (또는 기존 `/ws/projects/{id}/tasks/{taskId}/logs` 재사용). `?since=<seq>` 쿼리 파라미터 처리 |
| `LogHub` (server) | Service | 기존 SharedFlow는 유지. 별도의 `ConsoleEventBuffer` ring buffer 추가 (또는 LogHub 확장) |
| `ApiPath` (shared) | Const | `claudeConsolePrompt(projectId)`, `claudeConsoleNew(projectId)`, `wsConsole(projectId)`, `quickActions(projectId)` 신규 |
| `ProjectDetailScreen` (android) | Composable | `ProjectConsoleScreen`으로 교체 |
| `ClaudePromptScreen` (android) | Composable | 삭제 |
| `LogScreen` (android) | Composable | 콘솔 안 메시지 카드로 흡수, 독립 screen 삭제 |
| `Routes` (android nav) | Navigation | `Detail` → `Console` 라우트. `ClaudePrompt`, `Log` 라우트 제거 |
| `AndroidManifest.xml` | Permission | `RECORD_AUDIO` 권한 선언 |
| `strings.xml` | i18n | 콘솔 관련 신규 문자열 ~15개 |
| `<workspace>/.vibecoder/<projectId>/claude-session.id` | Filesystem | 신규 파일 |

### 6.2 Current Consumers

| Resource | Operation | Code Path | Impact |
|----------|-----------|-----------|--------|
| `claudeTasks` REST | POST (submit) | Android `ApiService.submitClaudeTask` → server `ClaudeRoutes` → `TaskQueue` → `ClaudeRunner.execute` | **Breaking 또는 deprecated**: 콘솔 모드로 교체 또는 병존 결정 필요 (이번 사이클: 신규 console 엔드포인트로 분리, 기존 task 엔드포인트는 1 사이클 deprecated 유지 후 다음 사이클에서 제거) |
| `claudeTasks` REST | GET (list/details/cancel) | Android UI에서 사용처: `LogScreen` (taskId 기반 구독), `ProjectDetailScreen`의 "최근 태스크" 섹션 | 화면이 사라지므로 함께 제거 |
| `wsTaskLogs` WS | subscribe | Android `WsClient` → `LogScreen` | console WS로 교체 |
| `ClaudeRunner.execute` | exec | `ClaudeRoutes` POST handler (queue executor) | 호출 경로 변경: 새 `ClaudeSessionManager.sendPrompt` 우선, one-shot 모드는 future fallback 용으로 보존 |
| `Builds` 흐름 (autoBuild) | exec | `ClaudeRoutes` 에서 `body.autoBuild` true 시 `buildService.runDebug` 호출 | autoBuild 옵션은 console 모드에서도 유지. 프롬프트 응답 후 빌드 트리거. UI는 토글 또는 퀵 칩으로 노출 |

### 6.3 Verification

- [ ] 기존 빌드/Git/파일/아티팩트/페어링 등 다른 화면 정상 동작 유지 (콘솔 변경의 부수효과 없음)
- [ ] `ApiPath` 변경이 안드로이드/서버 양측에 동시 반영 (shared 모듈)
- [ ] task 기반 코드 잔재(`TaskRow`, `TaskQueue`, `TaskRepository`)는 빌드 흐름에 여전히 쓰이므로 **콘솔 기능과 무관하게 보존**

---

## 7. Architecture Considerations

### 7.1 Project Level Selection

| Level | Characteristics | Recommended For | Selected |
|-------|-----------------|-----------------|:--------:|
| **Starter** | Simple structure (`components/`, `lib/`, `types/`) | Static sites, portfolios, landing pages | ☐ |
| **Dynamic** | Feature-based modules, BaaS-like 패턴, 3계층 | Web/mobile apps with backend | **☑** |
| **Enterprise** | Strict layer separation, DI, microservices | High-traffic systems | ☐ |

본 프로젝트(vibe-coder)는 Ktor 서버 + Android Compose + shared DTO의 **Dynamic 수준 모노레포**. 본 기능은 그 위 확장 모듈이므로 동일 Level 유지.

### 7.2 Key Architectural Decisions

| Decision | Options | Selected | Rationale |
|----------|---------|----------|-----------|
| Claude 실행 모드 | (a) one-shot `claude -p` 매회 + `--resume` (b) interactive PTY (c) `--print + stream-json + --resume` 영속 | **(c)** | (a)는 cold-start latency·이벤트 누락 / (b)는 PTY 의존성 / (c)는 JSON 이벤트 + 영속 + 표준 stdio라 가장 깔끔 |
| WS 이벤트 직렬화 | (a) sealed `WsFrame` + `@SerialName` (b) 자유 JsonObject pass-through | **(a) + (b) fallback** | 알려진 type은 정의된 frame, 미지 type은 `JsonElement raw` 필드 통째 전달하여 클라이언트가 표시. CLI 버전 변동 대응 |
| 이벤트 버퍼 | (a) in-memory ring 200건 (b) disk WAL | **(a)** | MVP 충분. 디스크 영속은 R-04 mitigation으로 향후 옵션 |
| 음성 입력 | (a) Android SpeechRecognizer (b) Whisper API | **(a)** | 추가 비용/네트워크 없음. 한/영 자동 |
| 액션 정의 모델 | (a) 안드로이드 하드코딩 (b) shared sealed objects (c) 서버 JSON manifest + API (d) 다형 `sealed ProjectAction` 클래스 트리 + manifest + plugin discovery | **(d)** | 사용자 결정 보강: "MCP 도구·사용자 프롬프트 등 다양한 편의 기능 다량 추가 예정, 확장성 염두". 단순 프롬프트만 보내는 manifest로는 부족 → 액션 타입 다형화 + MCP tool auto-discovery + 사용자 매니페스트 오버라이드 |
| 액션 카테고리 그룹핑 | (a) flat list (b) tree(카테고리 → 액션) | **(b)** | 향후 액션이 수십 개로 늘면 flat 칩 줄로는 가독성 ↓. 안드로이드 UI는 카테고리 탭 + 각 탭의 LazyRow chips로 렌더 |
| MCP 통합 방식 | (a) 별도 화면 (b) 액션 시스템에 통합 | **(b)** | MCP 도구도 "원터치로 호출되는 편의 기능"이라는 본질이 동일. 액션 레지스트리에 자동 등록하면 사용자 학습곡선 ↓ |
| 매니페스트 위치 | (a) 안드로이드 assets (b) 서버 resources (c) 서버 resources + workspace user 매니페스트 병합 | **(c)** | 시스템 기본은 서버 resources, 사용자 추가는 workspace 파일 1개. CI 없이도 사용자가 파일만 수정해서 즉시 추가 가능 |
| 안드로이드 화면 구조 | (a) Detail+Prompt+Log 별도 (b) 채팅형 통합 | **(b)** | 사용자 결정: 상단 콘솔 + 하단 입력 |
| 메시지 렌더링 | (a) 단일 텍스트 컴포저블 (b) event type별 카드 | **(b)** | tool_use/tool_result/error를 텍스트로만 표시하면 정보 손실. 카드형이 모바일 가독성 ↑ |
| 세션 ID 저장 | (a) DB column (b) workspace 파일 | **(b)** | `<workspace>/.vibecoder/<projectId>/claude-session.id`. 워크스페이스 단위로 자족적이고 파일 1줄 plain text로 디버그 용이 |
| 재기동 정책 | (a) 명시 사용자 액션만 (b) idle timeout + 자동 resume | **(b)** | 리소스 절약. session-id로 언제든 resume 가능하므로 사용자 인지 없이 동작 |

### 7.3 Module Layout

```
Project Level: Dynamic (existing structure preserved)

Server additions (server/src/main/kotlin/com/siamakerlab/vibecoder/server):
├─ claude/
│  ├─ ClaudeRunner.kt              ← 기존 one-shot (보존, deprecated 표시)
│  ├─ ClaudeSessionManager.kt      ← NEW. 프로세스 lifecycle + stdin/stdout 파이프
│  ├─ ClaudeStreamParser.kt        ← NEW. stream-json line → ClaudeEvent sealed class
│  ├─ ClaudeEvent.kt               ← NEW. sealed class hierarchy
│  └─ ClaudeConsoleRoutes.kt       ← NEW. POST prompt + POST new-session
├─ quickactions/
│  ├─ QuickAction.kt               ← NEW. data class
│  ├─ QuickActionRegistry.kt       ← NEW. 기본 5개 + 추후 YAML 로드 hook
│  └─ QuickActionRoutes.kt         ← NEW. GET /api/projects/{id}/quick-actions
└─ ws/
   ├─ LogHub.kt                    ← MOD. seq counter + ring buffer per project console topic
   └─ WsRoutes.kt                  ← MOD. /ws/projects/{id}/console/logs + ?since=

Shared additions (shared/src/main/kotlin/com/siamakerlab/vibecoder/shared):
├─ ApiPath.kt                      ← MOD. consolePrompt, consoleNew, wsConsole, quickActions
├─ dto/QuickActionDto.kt           ← NEW
├─ dto/ConsoleDto.kt               ← NEW (UserPromptDto, etc.)
└─ ws/WsFrame.kt                   ← MOD. Console-specific subtypes

Android additions (android-app/app/src/main/kotlin/com/siamakerlab/vibecoder/console):
├─ ui/console/                     ← NEW package
│  ├─ ProjectConsoleScreen.kt      ← top conversation + bottom input
│  ├─ ConsoleViewModel.kt
│  ├─ ConsoleEvent.kt              ← UI-side event model
│  ├─ messages/
│  │  ├─ AssistantBubble.kt
│  │  ├─ ToolUseCard.kt
│  │  ├─ ToolResultCard.kt
│  │  ├─ ErrorBanner.kt
│  │  └─ SystemNotice.kt
│  ├─ input/
│  │  ├─ PromptInputBar.kt
│  │  ├─ VoiceButton.kt            ← SpeechRecognizer wrapper
│  │  └─ QuickActionChips.kt       ← LazyRow + chip 모듈 동적 로드
│  └─ scroll/
│     └─ AutoScrollState.kt        ← 사용자 스크롤 감지 + jump-to-latest
├─ data/repository/ConsoleRepository.kt   ← NEW (WS 송수신 + replay)
├─ data/remote/ConsoleWsClient.kt         ← NEW (Ktor client WS, --since= cursor)
└─ ui/nav/Routes.kt                       ← MOD. Console replaces Detail/Prompt/Log

Workspace filesystem changes:
└─ <workspace>/.vibecoder/<projectId>/
   ├─ project.yml                  ← existing
   ├─ logs/, builds/, patches/...  ← existing
   └─ claude-session.id            ← NEW (single-line file)
```

---

## 8. Convention Prerequisites

### 8.1 Existing Project Conventions

- [x] 글로벌 `CLAUDE.md` (Sia Makerlab Android 규칙) 적용 (`/home/wody/.claude/CLAUDE.md`)
- [x] `docs/01-plan/features/vibe-coder-mvp.plan.md` 존재
- [ ] `docs/01-plan/conventions.md` — 없음 (필요 시 후속 phase에서 정의)
- [x] `gradle/libs.versions.toml` 단일 진실 원천
- [x] Compose Material3, Hilt, Ktor 사용 중

### 8.2 Conventions to Define/Verify

| Category | Current State | To Define | Priority |
|----------|---------------|-----------|:--------:|
| **WS event 명명** | 기존 5종 (auth/log/done/error/ping) | 콘솔 신규 type 7~10종에 대한 일관 명명 (UserPrompt, Assistant, ToolUse, ToolResult, SessionStarted, ProcessTerminated, Replay, Heartbeat) | High |
| **퀵 액션 manifest 포맷** | 없음 | 서버 응답 JSON 스키마 (id/label/iconName/promptTemplate/order/visibleWhen?) | High |
| **세션 파일 포맷** | 없음 | `claude-session.id` = 단일 행 평문 (UUID-like) | Medium |
| **에러 코드** | `ApiErrorDto.code` 기존 `route_not_found` 등 | 콘솔용 신규 코드: `claude_unavailable`, `session_resume_failed`, `process_crashed` | Medium |
| **로깅 형식** | logback 기본 | `ClaudeSessionManager`는 프로세스 PID/projectId/exit code 명시 | Low |

### 8.3 Environment Variables Needed

| Variable | Purpose | Scope | To Be Created |
|----------|---------|-------|:-------------:|
| `CLAUDE_CMD` | `claude` 실행 파일 override (현 `auto` resolve와 병존) | Server | 기존 |
| `VIBECODER_CONSOLE_BUFFER_SIZE` | ring buffer 크기 override | Server | ☑ |
| `VIBECODER_CONSOLE_IDLE_MINUTES` | idle timeout (분) | Server | ☑ |

기본값은 코드 상수로 두고 환경변수는 운영 튜닝용으로 도입.

### 8.4 Pipeline Integration

이 사이클은 vibe-coder MVP의 확장 기능이므로 9-phase 파이프라인은 미적용. 단일 PDCA 사이클로 진행.

---

## 9. Future Extensions (TODO Roadmap)

본 사이클에 아키텍처만 준비하고, 다음 사이클들에서 manifest 파일 추가만으로 출시 가능한 항목:

### 9.1 Claude Slash Commands (인프라 기반, manifest 추가만으로 노출)

| Command | 우선순위 | 비고 |
|---------|:--------:|------|
| `/status` | High (본 사이클 데모 포함) | 사용량/플랜 표시. 콘솔 상단 패널 + 리프레시 |
| `/cost` | High | 누적 비용 표시 |
| `/model` | Medium | 현재 모델 + 변경 옵션 표시 |
| `/clear` | Medium | "Start new session" 버튼과 동등하나 컨벤션상 별도 노출 |
| `/memory` | Medium | 메모리 항목 조회/추가 (BaaS-like UI 필요) |
| `/plan`, `/exit-plan` | Low | Plan mode 토글 |
| `/agents`, `/permissions`, `/login`, `/help` | Low | 기능별 모달 |

각 항목은 `InvokeClaudeSlashCommandAction` 라우팅 인프라 + JSON manifest 한 줄 추가로 완성. 정밀 출력 파싱이 필요한 경우 별도 파서 모듈 추가.

### 9.2 MCP Tools (auto-discovery + manifest override)

- 서버 `.mcp.json`에 등록된 MCP 서버의 tool list를 시작 시점에 자동 스캔 → `InvokeMcpToolAction`으로 자동 등록
- 자주 쓰는 tool은 manifest에 `pinned: true`로 두어 첫 화면에 표시
- 후속: 안드로이드에서 tool argument 입력 폼 자동 생성 (JSON Schema 기반)

### 9.3 User Snippets / Prompt Templates

- `<workspace>/.vibecoder/actions.user.json`에 `SnippetInsertAction` 다수 추가 가능
- 후속: 안드로이드 길게 누르기 → "현재 입력을 스니펫으로 저장" 기능 (workspace 파일에 자동 append)

### 9.4 기타

- 응답 도중 Stop 버튼 (`POST .../claude/console/interrupt` → 프로세스 SIGINT)
- 이미지/파일 첨부 (안드로이드 갤러리 → 워크스페이스 업로드 → 프롬프트에 경로 참조)
- 세션 사이드바 (직전 세션 히스토리, swipe로 열기)
- 멀티 디바이스 동시 사용 시 cursor sync
- 모델 전환 UI (모델별 cost 비교 + 핀)

---

## 10. Next Steps

1. [ ] `/pdca design project-claude-console` — 본 Plan 기반 설계 문서 작성 (3 옵션 비교 + Sequence Diagram + Module Map)
2. [ ] 설계 검토 후 `/pdca do project-claude-console --scope module-A` 부터 단계 구현
3. [ ] **Phase A** (서버 핵심): `ClaudeSessionManager` + `ClaudeStreamParser` + `WsFrame` 확장 + ring buffer + `/api/projects/{id}/claude/console/*` 엔드포인트
4. [ ] **Phase B** (안드로이드 핵심): `ProjectConsoleScreen` + ViewModel + WS client + 메시지 카드 렌더
5. [ ] **Phase C** (액션 시스템): `ProjectAction` sealed class + manifest 로더 + `/api/projects/{id}/actions` + 안드로이드 chip/팔레트 UI
6. [ ] **Phase D** (모바일 UX): 음성 입력 + 스크롤 잠금 + IME 적응
7. [ ] **Phase E** (확장 데모): `/status` 패널 + MCP auto-discovery 데모 + 사용자 manifest 핫리로드
8. [ ] 각 Phase 후 `bkit:gap-detector` Gap 측정
9. [ ] Match Rate ≥ 90% 도달 후 `/pdca report project-claude-console`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-05-18 | Initial draft. PDCA Plan 단계 — 4개 확인 질문 답변 반영(모듈화 퀵액션 + 음성 SpeechRecognizer + ring buffer + 채팅형 UI). | wody |
