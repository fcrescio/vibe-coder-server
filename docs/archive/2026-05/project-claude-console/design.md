# project-claude-console Design Document

> **Summary**: Pragmatic 아키텍처 기반 — 프로젝트당 1개 영속 `claude` 프로세스, sealed-class 기반 다형 액션 레지스트리(매니페스트 + 워크스페이스 오버라이드 + mtime 핫리로드), 단일 WebSocket 양방향 채널 + ring buffer.
>
> **Project**: vibe-coder
> **Version**: 0.2.0
> **Author**: wody
> **Date**: 2026-05-18
> **Status**: Draft
> **Planning Doc**: [project-claude-console.plan.md](../../01-plan/features/project-claude-console.plan.md)

### Pipeline References

| Phase | Document | Status |
|-------|----------|--------|
| Phase 1 | Schema Definition | N/A |
| Phase 2 | Coding Conventions | N/A (uses global `~/.claude/CLAUDE.md`) |
| Phase 3 | Mockup | N/A (defined in §11.4 inline) |
| Phase 4 | API Spec | N/A (defined in §4 inline) |

---

## Context Anchor

| Key | Value |
|-----|-------|
| **WHY** | 현 분리된 화면 + 세션 무유지 구조로는 모바일에서 Claude Code의 연속 대화 경험을 재현 불가 |
| **WHO** | vibe-coder 단일 사용자 (sia@siamakerlab.com) — PC 서버 + 안드로이드 클라이언트 LAN 페어 |
| **RISK** | (1) Claude CLI stream-json/슬래시 포맷 변동 (2) 프로세스 leak/crash 복구 (3) 동시 다중 디바이스 충돌 (4) WS 끊김 시 이벤트 손실 (5) 액션 ecosystem 비대화 |
| **SUCCESS** | 한 세션 5회 연속 프롬프트 컨텍스트 유지 / 재접속 후 200건 이벤트 복원 / "Start new session" 후 새 session-id / 음성 한·영 각 1회 / 기본 6액션 + MCP auto-discovery 동작 / `/status` 데모 패널 / manifest 핫리로드 |
| **SCOPE** | Phase A 서버 세션 매니저 → Phase B 안드로이드 콘솔 → Phase C 액션 시스템 → Phase D 모바일 UX → Phase E 확장 데모(/status + MCP) |

---

## 1. Overview

### 1.1 Design Goals

- **세션 영속성**: 프로젝트당 1개의 `claude` 자식 프로세스를 lifecycle 관리하여 모바일 클라이언트 측 cold-start 0회.
- **확장 가능한 액션 시스템**: 새 액션(MCP tool, 슬래시 커맨드, 사용자 스니펫) 추가가 manifest 파일 추가/편집만으로 가능, 코드 변경·앱 재배포 불필요.
- **재접속 견고성**: WS 끊김 ↔ 재접속 시 누락 이벤트를 단조증가 seq + ring buffer로 복원.
- **모바일 친화 UX**: 채팅형 콘솔, 음성 입력, IME 적응, 스크롤 잠금.
- **기존 패턴 유지**: 신규 Clean 4-tier 도입 없이 현 3-layer (Ktor route → Service → Repository) + util 격리 패턴 그대로.

### 1.2 Design Principles

- **YAGNI**: 미래 가능성을 위한 4-tier abstraction 도입하지 않음. 확장이 정확히 요구되는 지점(액션·이벤트 타입)에만 sealed class 사용.
- **단일 책임**: `ClaudeSessionManager`는 프로세스 lifecycle만, `ClaudeStreamParser`는 JSON 파싱만, `ProjectActionRegistry`는 매니페스트 로드/병합만 담당.
- **확장 지점은 데이터로 표현**: 액션 manifest는 JSON 파일, 슬래시 커맨드는 액션 타입 분기, MCP는 `.mcp.json` 스캔 — 모두 코드 외부 데이터.
- **장애 격리**: Claude 프로세스 crash가 서버 자체를 죽이지 않음. WS 끊김이 세션을 죽이지 않음.
- **path-safety 일관**: 모든 디스크 접근은 `WorkspacePath`/`PathSafety` 경유.

---

## 2. Architecture Options

### 2.0 Architecture Comparison

| Criteria | A: Minimal | B: Clean | **C: Pragmatic** ⭐ |
|----------|:-:|:-:|:-:|
| **Approach** | 기존 one-shot + `--resume` 만 추가 | UseCase 계층 + 4 WS 채널 + 풀 플러그인 discovery | sealed 다형 + manifest 핫리로드 + 단일 WS |
| **New Files** | ~8 | ~35 | ~19 |
| **Modified Files** | ~6 | ~25 | ~14 |
| **Complexity** | Low | High | Medium |
| **Maintainability** | Medium | High | High |
| **Effort (sub-phases)** | 2 | 6 | 4 |
| **세션 영속성** | ❌ (cold start 1~2s 잔존) | ✅ | ✅ |
| **확장 ecosystem 부합도** | 낮음 | 매우 높음 | 높음 |
| **Risk** | 중 (Plan §5 R-01/R-08 부분 미스) | 낮음 (추상화 과다) | 낮음 (필요지점만 추상화) |
| **Recommendation** | 빠른 PoC | 장기 대규모 | **Default — 본 사이클 선택** |

**Selected**: **Option C — Pragmatic Balance**.

**Rationale**:
- Plan §1.2(SUCCESS) — 세션 영속, manifest 핫리로드, MCP auto-discovery, `/status` 데모 모두 충족.
- Plan §3.1 FR-01~16의 sealed class·manifest 요구는 그 정확한 지점에 abstraction 비용 지불.
- Plan §7.1 — 본 프로젝트는 Dynamic 수준. UseCase 계층(B)는 솔로 MVP에 과다.
- 사용자 명시: "Iterate 단계까지 추가 질의 없이 권장사항으로 자율 진행" → 권장사항 채택.

---

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                      Android App                            │
│                                                             │
│  ProjectConsoleScreen (Compose)                             │
│  ┌─────────────────────────────────────────┐                │
│  │ Top: ConversationList                   │                │
│  │   ├─ AssistantBubble                    │                │
│  │   ├─ ToolUseCard / ToolResultCard       │                │
│  │   ├─ ErrorBanner / SystemNotice         │                │
│  │   └─ AutoScrollState                    │                │
│  │ Bottom: PromptInputBar                  │                │
│  │   ├─ VoiceButton (SpeechRecognizer)     │                │
│  │   ├─ TextField (imePadding)             │                │
│  │   ├─ QuickActionChips (LazyRow×N cats)  │                │
│  │   └─ Send / New-Session                 │                │
│  └─────────────────────────────────────────┘                │
│           ▲             │             ▲                     │
│           │ WS recv     │ WS send     │ HTTP GET            │
│           │             ▼             │                     │
│  ConsoleViewModel ──── ConsoleRepository ─── ActionsRepo    │
│           │                                                 │
│           ▼ Ktor Client (OkHttp + WebSockets)               │
└───────────│─────────────────────────────────────────────────┘
            │
            │   LAN  ●  Bearer auth (first WS frame)
            │
┌───────────▼─────────────────────────────────────────────────┐
│                      Server (Ktor)                          │
│                                                             │
│  /ws/projects/{id}/console/logs?since=<seq>                 │
│  /api/projects/{id}/claude/console/prompt   (POST*)         │
│  /api/projects/{id}/claude/console/new      (POST)          │
│  /api/projects/{id}/claude/status           (GET)           │
│  /api/projects/{id}/actions                 (GET)           │
│                                                             │
│  * 1차: WS frame 으로도 수신. POST 는 fallback              │
│           │                                                 │
│           ▼                                                 │
│     ConsoleRoutes ─────────┐                                │
│           │                │                                │
│           ▼                ▼                                │
│   ClaudeSessionManager   ProjectActionRegistry              │
│     (1 process/project)   ├─ resources manifests            │
│           │               ├─ workspace user manifest        │
│           │               └─ mtime watcher (hot reload)     │
│           ▼                                                 │
│   spawn claude --print --output-format stream-json          │
│         --input-format stream-json [--resume <id>]          │
│           │                                                 │
│           ▼  stdout (line-delimited JSON)                   │
│     ClaudeStreamParser                                      │
│           │  → ClaudeEvent (sealed)                         │
│           ▼                                                 │
│   ConsoleHub (LogHub extension: seq + ring buffer 200)      │
│           │                                                 │
│           ▼  WsFrame.Console.* (sealed sub-frames)          │
│      WS subscribers (per project)                           │
│                                                             │
│  session-id persistence:                                    │
│    <workspace>/.vibecoder/<projectId>/claude-session.id     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Key Server Components

#### 2.2.1 ClaudeSessionManager
- 책임: 프로세스 spawn / stdin 주입 / stdout 라인 수신 / lifecycle (crash 감지, idle timeout, SIGTERM/SIGKILL).
- 상태: `ConcurrentHashMap<projectId, Session>` 단일 인스턴스. `Session` = `Process` + `BufferedWriter(stdin)` + `BufferedReader(stdout)` + `Job(stdout-read)` + `Mutex(stdin-write)` + `lastActivity: Instant` + `sessionId: String?`.
- 핵심 API:
  - `suspend fun sendPrompt(projectId, text): Unit` — 세션 없으면 spawn(+ resume), stdin에 JSON 한 줄 inject.
  - `suspend fun startNew(projectId): Unit` — 현 세션 SIGTERM(5s→SIGKILL) + session-id 파일·ring buffer 삭제.
  - `suspend fun shutdown()` — 서버 종료 시 모든 세션 SIGTERM.
  - `fun isAlive(projectId): Boolean` — 콘솔 패널 상태 표시용.
- 핵심 규칙: stdin 주입은 per-project mutex로 직렬화 (동일 프로젝트 다중 디바이스가 동시 송신해도 안전). idle 30분 경과 시 백그라운드 coroutine이 SIGTERM (session-id는 보존).

#### 2.2.2 ClaudeStreamParser
- 책임: stdout 한 줄 = 1 JSON 객체로 가정하고 파싱 → `ClaudeEvent` sealed class.
- 폴리시: 미지의 type은 `ClaudeEvent.Unknown(raw: JsonElement)`로 wrap하여 클라이언트에 그대로 전달 (CLI 버전 변동 대응).
- `ClaudeEvent` 종류 (1차):
  - `SessionStarted(sessionId, model, cwd)`
  - `AssistantMessage(text, isPartial: Boolean)` — 스트리밍 청크
  - `ToolUse(toolName, input: JsonElement, toolUseId)`
  - `ToolResult(toolUseId, output: JsonElement, isError: Boolean)`
  - `Error(code, message)`
  - `Done(reason: String)` — turn 완료 표시
  - `Unknown(raw)`

#### 2.2.3 ConsoleHub (LogHub 확장)
- 기존 `LogHub`는 topic별 `MutableSharedFlow<WsFrame>`만 보유. 이번 사이클에 추가:
  - 단조증가 `AtomicLong` seq per topic.
  - 200-element bounded `ArrayDeque<WsFrameWithSeq>` per topic (in-memory, lock-protected).
  - `subscribeFrom(topic, since: Long)`: ring buffer에서 since 이후 이벤트 replay + 이후는 SharedFlow.
- 새 topic 키 컨벤션: `console-<projectId>` (기존 `task-<taskId>`와 분리).

#### 2.2.4 ProjectActionRegistry
- 책임: 서버 resources의 매니페스트 다수 + workspace `actions.user.json` 병합 → `ProjectAction` 트리.
- 자동발견:
  - `.mcp.json`에 등록된 MCP 서버의 tool list → `InvokeMcpToolAction`으로 자동 등록 (category=`MCP`).
- 핫리로드:
  - workspace 매니페스트 mtime 폴링 (10초 주기). 변경 시 캐시 무효화.
- API: `fun listForProject(projectId): ActionTree` — `Map<Category, List<ProjectAction>>` 직렬화 형태.

#### 2.2.5 ClaudeStatusService (Phase E 데모)
- 책임: `claude /status` (또는 동등 수단) 호출 + 결과 파싱 + 60초 TTL 캐시.
- 폴백 전략: stream-json input이 슬래시 커맨드 수용하지 않으면 별도 one-shot `claude /status` 호출 (영속 세션과 무관한 사이드채널).

### 2.3 Key Android Components

```
ui/console/
  ProjectConsoleScreen           # Scaffold + LazyColumn(top) + Surface(bottom)
  ConsoleViewModel               # StateFlow<ConsoleUiState>
  ConsoleUiState                 # messages, isProcessActive, statusInfo, scrollLocked
  messages/                      # event-type별 Composable
    AssistantBubble
    ToolUseCard
    ToolResultCard
    ErrorBanner
    SystemNotice
    UnknownEventCard
  input/
    PromptInputBar               # imePadding + Send + New-Session
    VoiceButton                  # SpeechRecognizer wrapper + permission flow
    QuickActionChips             # 카테고리 탭 + LazyRow chips
  scroll/
    AutoScrollState              # 사용자 스크롤 감지 + jump-to-latest
  status/
    StatusPanel                  # collapsible /status 표시 + refresh
  
data/
  remote/
    ConsoleWsClient              # Ktor client WS + since= cursor
  repository/
    ConsoleRepository            # WS state machine (connect/auth/replay/stream)
    ActionsRepository            # HTTP GET /actions, in-memory cache
    ClaudeStatusRepository       # HTTP GET /claude/status, 60s cache
```

---

## 3. Data Model

### 3.1 Filesystem

| Path | Format | Lifecycle |
|------|--------|-----------|
| `<workspace>/.vibecoder/<projectId>/claude-session.id` | 단일 행 평문 (Claude의 session-id) | "Start new session" 또는 resume 실패 시 삭제 |
| `<workspace>/.vibecoder/actions.user.json` | JSON (사용자 정의 액션) | 사용자 직접 편집 |
| `server/src/main/resources/actions/*.json` | JSON (시스템 기본 액션) | 코드와 함께 배포 |

### 3.2 In-Memory

| Structure | Owner | TTL |
|-----------|-------|-----|
| `ConcurrentHashMap<projectId, Session>` | ClaudeSessionManager | idle 30분 |
| `ArrayDeque<WsFrameWithSeq>` per topic | ConsoleHub | 200건 또는 topic 종료 |
| Action tree cache | ProjectActionRegistry | manifest mtime 변경 시 invalidate |
| Status cache | ClaudeStatusService | 60초 |

### 3.3 No DB Schema Changes

기존 `Projects`, `Tasks`, `Builds`, `Artifacts`, `Devices`, `UploadedFiles` 테이블은 변경 없음. console 흐름은 in-memory + filesystem만 사용. (이유: task 모델은 one-shot의 자취가 강해 console에 부적합. 새 console 흐름은 conversation 자체가 영속 단위.)

---

## 4. API Specification

### 4.1 REST Endpoints (신규)

| Method | Path | 요청 본문 | 응답 |
|--------|------|----------|------|
| POST | `/api/projects/{projectId}/claude/console/prompt` | `{ "text": "..." }` | `202 Accepted` + `{ "seq": <next-seq-baseline> }` |
| POST | `/api/projects/{projectId}/claude/console/new` | (empty) | `202 Accepted` |
| GET | `/api/projects/{projectId}/claude/status` | — | `ClaudeStatusDto` (json) |
| GET | `/api/projects/{projectId}/actions` | — | `ActionTreeDto` |

기존 `/api/projects/{id}/claude/tasks` (one-shot task) 는 **deprecated 표시**만 (당분간 보존, 다음 사이클에서 제거).

### 4.2 WebSocket Channel

**URL**: `ws://<host>:17880/ws/projects/{projectId}/console/logs?since=<seq>`

**Auth (변경 없음)**: 첫 프레임 `{"type":"auth","token":"<bearer>"}` 5초 타임아웃.

**Server → Client (sealed `WsFrame.Console` 신규)**:

| Type (SerialName) | Payload | 의미 |
|---|---|---|
| `console_session_started` | `sessionId, model, cwd, seq` | Claude 세션 시작 (resume 또는 new) |
| `console_assistant` | `text, isPartial, seq` | Claude 응답 텍스트 청크 |
| `console_tool_use` | `toolName, input(JsonElement), toolUseId, seq` | tool 호출 |
| `console_tool_result` | `toolUseId, output(JsonElement), isError, seq` | tool 결과 |
| `console_error` | `code, message, seq` | Claude 측 오류 |
| `console_done` | `reason, seq` | turn 완료 |
| `console_unknown` | `raw(JsonElement), seq` | 미지의 CLI event passthrough |
| `console_system` | `code(String), message, seq` | 서버 발 시스템 알림 (process_crashed, process_restarted, idle_terminated 등) |
| `console_replay_begin` | `fromSeq, toSeq` | 재접속 replay 시작 |
| `console_replay_end` | (empty) | replay 종료, 이후 live |
| `ping` | — | keep-alive (기존 유지) |

**Client → Server**:

| Type | Payload | 의미 |
|---|---|---|
| `auth` | `token` | (필수, 기존 유지) |
| `user_prompt` | `text` | 프롬프트 송신 (POST와 동등, WS만 쓰는 클라이언트용) |
| `action_invoke` | `actionId, params(JsonElement)?` | 액션 실행 (서버가 type 분기하여 처리) |

### 4.3 Action Tree (응답 예시)

```json
{
  "categories": [
    {
      "id": "build",
      "label": "Build",
      "icon": "Hammer",
      "actions": [
        {
          "type": "RunServerAction",
          "id": "build:debug",
          "label": "Debug build",
          "icon": "Play",
          "serverAction": "build.debug",
          "requires": ["build"]
        }
      ]
    },
    {
      "id": "git",
      "label": "Git",
      "icon": "GitBranch",
      "actions": [
        { "type": "RunServerAction", "id": "git:status", "label": "Status", "icon": "Eye", "serverAction": "git.status" },
        { "type": "RunServerAction", "id": "git:commit", "label": "Commit", "icon": "Check", "serverAction": "git.commit" }
      ]
    },
    {
      "id": "claude",
      "label": "Claude",
      "actions": [
        { "type": "SendPromptAction", "id": "prompt:explain-last-error", "label": "Explain last error", "icon": "HelpCircle",
          "promptTemplate": "Look at the most recent build/test output and explain the error..." },
        { "type": "InvokeClaudeSlashCommandAction", "id": "slash:status", "label": "/status", "icon": "Activity",
          "command": "status" }
      ]
    },
    {
      "id": "mcp",
      "label": "MCP",
      "actions": [ /* auto-discovered from .mcp.json */ ]
    },
    {
      "id": "snippets",
      "label": "My snippets",
      "actions": [ /* user-defined from actions.user.json */ ]
    }
  ]
}
```

### 4.4 Shared DTOs (kotlinx-serialization)

```kotlin
// shared/dto/ConsoleDtos.kt
@Serializable data class PromptRequestDto(val text: String)
@Serializable data class PromptAcceptedDto(val seq: Long)
@Serializable data class ClaudeStatusDto(
    val sessionId: String?, val processAlive: Boolean,
    val model: String?, val plan: String?, val quotaRemaining: String?,
    val updatedAt: String,
)

// shared/dto/ProjectActionDto.kt — sealed
@Serializable sealed class ProjectActionDto {
    abstract val id: String; abstract val label: String; abstract val icon: String?
    @Serializable @SerialName("SendPromptAction")
    data class SendPrompt(override val id, override val label, override val icon,
        val promptTemplate: String, val variables: List<String> = emptyList()) : ProjectActionDto()
    @Serializable @SerialName("InvokeMcpToolAction")
    data class InvokeMcpTool(override val id, override val label, override val icon,
        val mcpServer: String, val toolName: String, val argsTemplate: JsonElement? = null) : ProjectActionDto()
    @Serializable @SerialName("RunServerAction")
    data class RunServerAction(override val id, override val label, override val icon,
        val serverAction: String, val params: JsonElement? = null) : ProjectActionDto()
    @Serializable @SerialName("OpenPaletteAction")
    data class OpenPalette(override val id, override val label, override val icon,
        val paletteId: String) : ProjectActionDto()
    @Serializable @SerialName("SnippetInsertAction")
    data class SnippetInsert(override val id, override val label, override val icon,
        val text: String) : ProjectActionDto()
    @Serializable @SerialName("InvokeClaudeSlashCommandAction")
    data class InvokeClaudeSlashCommand(override val id, override val label, override val icon,
        val command: String) : ProjectActionDto()
}
```

---

## 5. Sequence Diagrams

### 5.1 첫 프롬프트 (세션 없음 → spawn)

```
Android        ConsoleRoutes    SessionManager      claude(child)     ConsoleHub      WS subs
   │                │                │                   │                │             │
   │── WS auth ────▶│                │                   │                │             │
   │   200 OK       │                │                   │                │             │
   │── prompt ─────▶│── sendPrompt ─▶│                   │                │             │
   │                │                │── spawn (no id) ─▶│                │             │
   │                │                │                   │                │             │
   │                │                │◀── stdout {type:"system","subtype":"init",sessionId:"X"}
   │                │                │── parse ──────────────────────────▶│             │
   │                │                │                                    │── frame ───▶│ console_session_started
   │                │                │── persist X to claude-session.id   │             │
   │                │                │                   │                │             │
   │                │                │── stdin {type:"user","content":"<text>"} ────────▶│
   │                │                │                   │                │             │
   │                │                │                   │── reasoning, tool_use, tool_result, ...
   │                │                │── parse ──────────▶│── push frames ▶│── stream ──▶│
   │                │                │                   │                │             │
   │                │                │                   │── {type:"result","done":true}
   │                │                │── parse ──────────▶│── push ───────▶│── done ────▶│ console_done
```

### 5.2 재접속 (WS 끊김 → since=N replay)

```
Android         ConsoleHub
   │                │
   │── WS connect ─▶│  (URL contains ?since=42)
   │── auth        ─▶│
   │                │── replay_begin(fromSeq=43, toSeq=lastSeq) ─▶│
   │                │── frame seq=43 ──▶│ ... up to lastSeq        │
   │                │── replay_end ────▶│
   │                │── (live stream resumes)                      │
```

### 5.3 Start new session

```
Android        ConsoleRoutes    SessionManager      claude(child)
   │── POST new ──▶│── startNew ──▶│                   │
   │                │                │── SIGTERM ──────▶│ (5s grace)
   │                │                │── still alive? SIGKILL
   │                │                │── delete claude-session.id
   │                │                │── ring buffer clear (console-<id> topic)
   │                │── 202 ────────▶│
   │── next prompt ▶│── sendPrompt ─▶│── spawn (no resume) → new sessionId
```

### 5.4 Crash 복구

```
SessionManager     claude(child)
   │                │
   │                │── crash / exit code 137
   │── exit observed by stdout-read job (EOF)
   │── emit ConsoleSystem "process_crashed" frame
   │── keep claude-session.id intact
   │
   │ (next prompt arrives)
   │── sendPrompt ▶│── spawn (--resume X) → resume succeeds
   │   OR resume fails (exit code + stderr match):
   │     - delete claude-session.id
   │     - emit ConsoleSystem "resume_failed_starting_new"
   │     - spawn (no resume) → new sessionId
```

### 5.5 액션 호출 (RunServerAction `build:debug`)

```
Android         ConsoleRoutes / BuildRoutes
   │── action_invoke {id:"build:debug"} ─▶│
   │                                       │── 분기: serverAction="build.debug"
   │                                       │── BuildService.submitDebug(projectId)
   │                                       │── 응답: BuildDto
   │                                       │── ConsoleHub.push SystemNotice {kind:"build_started", buildId}
   │◀── WsFrame.Console.System
```

### 5.6 액션 호출 (InvokeClaudeSlashCommandAction `/status`)

```
Android         ConsoleRoutes      ClaudeStatusService     claude (sidecar one-shot)
   │── action_invoke {id:"slash:status"} ─▶│
   │                                        │── cache miss → spawn `claude /status`
   │                                        │── parse output
   │                                        │── cache 60s
   │                                        │── 응답: ClaudeStatusDto
   │                                        │── ConsoleHub.push SystemNotice {kind:"status_refreshed", ...summary}
   │◀── (next status panel refresh shows new data)
```

---

## 6. Error Handling

| 시나리오 | 감지 | 동작 |
|---------|------|------|
| `claude` 미설치 | spawn IOException | `console_system code=claude_unavailable` + 사용자 안내 |
| 프로세스 비정상 종료 | stdout EOF + exit code != 0 | `console_system code=process_crashed`, session-id 보존, 다음 프롬프트 시 자동 resume |
| Resume 실패 | exit code + stderr 패턴 매칭 | session-id 삭제 + `code=resume_failed_starting_new` + 새 세션 spawn |
| stdout 줄이 JSON 파싱 실패 | `kotlinx.serialization` exception | `Unknown(raw=lineText)` 이벤트로 wrap, 디버그 로그 |
| WS 끊김 + since 누락 | client 미수신 | ring buffer 200건 초과분만 영구 손실. `console_system code=replay_partial` 정보 이벤트 |
| Action manifest 파싱 오류 | 부팅 시 catch | 해당 매니페스트 무시 + WARN 로그. 다른 매니페스트는 정상 로드 |
| 사용자 manifest 핫리로드 실패 | mtime 폴링 시 parse 오류 | 이전 캐시 유지 + WARN 로그 |
| Idle 30분 | 백그라운드 coroutine | SIGTERM + session-id 보존 + `code=idle_terminated` |
| 같은 프로젝트 다중 디바이스 동시 prompt | per-project stdin mutex | 직렬화 처리. 양 디바이스 모두 동일 stream 수신 |
| RECORD_AUDIO 권한 거부 | Android runtime | 마이크 버튼 disabled + 권한 안내 다이얼로그 |

모든 서버측 에러는 기존 `StatusPagesPlugin`의 `ApiException` / `BadRequestException` / `IllegalArgumentException` 매핑을 그대로 사용. `JsonConvertException` → 400 매핑도 그대로.

---

## 7. Security

- WS auth는 기존 `WsAuth`의 5초 첫 프레임 패턴 그대로 사용 (URL 토큰 없음).
- session-id는 평문 저장 가능 (Claude 내부 식별자, 디바이스 토큰과 다름). 단 워크스페이스 외부 경로 거부 (`PathSafety`).
- Action manifest 로드는 path-safe — workspace user manifest는 PathSafety로 검증된 경로에서만 읽음.
- `RunServerAction`은 화이트리스트 (`build.debug`, `git.status`, `git.commit`, `files.list` 등)만 허용. 임의 명령 실행 방지.
- `InvokeMcpToolAction`은 서버 `.mcp.json`에 이미 인가된 도구만 호출. 안드로이드가 새 MCP 서버 등록은 불가.
- `InvokeClaudeSlashCommandAction`은 화이트리스트 (`status`, `cost`, `model`, `clear`, `memory`, `plan`)만 허용. shell injection 방지.
- 모든 input은 길이 제한: prompt ≤ 32 KB, action params ≤ 4 KB.

---

## 8. Performance

| 지표 | 목표 | 측정 방법 |
|------|------|----------|
| 첫 프롬프트 latency (콜드) | < 2초 (Claude 자체 0.5~1s 포함) | curl + chrono |
| n번째 프롬프트 latency (웜) | < 500ms 서버 오버헤드 | 동일 |
| stream-json 이벤트 처리 처리량 | 100 events/sec without backpressure | LogHub 부하 테스트 |
| 메모리 | 1 영속 세션 + 200건 ring + 액션 캐시 < 50MB JVM heap | jcmd |
| WS 끊김 → 재접속 → replay 완료 | < 1초 (200건 전송) | manual |
| Action tree 로드 | < 100ms (manifest 5개 + MCP 5개) | unit test |

---

## 9. Testing Strategy

| 레벨 | 대상 | 도구 |
|------|------|------|
| Unit | `ClaudeStreamParser` (JSON → ClaudeEvent), `ProjectActionRegistry` (manifest merge), `ConsoleHub` (seq + ring buffer), `ClaudeSessionManager` (lifecycle + mutex) — mock Process | JUnit + Kotest + Turbine |
| Integration | `ConsoleRoutes` + `SessionManager` + 실 `echo`/`cat` 더미 프로세스 | Ktor test host + ProcessBuilder real |
| Manual | 안드로이드 ProjectConsoleScreen E2E (음성, 칩, 스크롤 잠금, IME, 재접속 — 비행기 모드 토글) | 실 디바이스 |
| Zero-Script QA | 실 Claude 프로세스 5회 연속 프롬프트 — Docker 로그 모니터링 | bkit:zero-script-qa |

핵심 unit 테스트는 Phase A 종료 시점에 작성 (TDD 강제가 아닌 핵심 lifecycle만).

---

## 10. Migration Plan

- 기존 `ClaudePromptScreen` + `LogScreen` 코드는 **이번 사이클 마지막에 일괄 제거** (Phase B 완료 후, Phase E 직전).
- 기존 `/api/projects/{id}/claude/tasks` 엔드포인트는 **deprecated 표시**만 추가, 응답 동작은 유지 (다음 사이클에서 제거).
- 기존 `TaskRow` 데이터는 보존 (autoBuild·diff 캡처에 여전히 쓰임).
- 안드로이드 nav `Routes` 상의 `Prompt`, `Log` 라우트는 제거, `Console` 추가. 진입 경로는 `ProjectList` 탭 → `Console` 직접.

---

## 11. Implementation Guide

### 11.1 신규 디렉토리/파일

```
shared/src/main/kotlin/com/siamakerlab/vibecoder/shared/
  ApiPath.kt                                   ← MOD (console 신규 경로)
  dto/ConsoleDtos.kt                           ← NEW
  dto/ProjectActionDto.kt                      ← NEW (sealed)
  ws/WsFrame.kt                                ← MOD (Console.* 서브타입)

server/src/main/kotlin/com/siamakerlab/vibecoder/server/
  claude/
    ClaudeRunner.kt                            ← MOD (deprecated 표시만)
    ClaudeSessionManager.kt                    ← NEW
    ClaudeStreamParser.kt                      ← NEW
    ClaudeEvent.kt                             ← NEW (sealed)
    ConsoleRoutes.kt                           ← NEW
    ClaudeStatusService.kt                     ← NEW (Phase E)
    ClaudeStatusRoutes.kt                      ← NEW (Phase E)
  actions/
    ProjectAction.kt                           ← NEW (sealed)
    ProjectActionRegistry.kt                   ← NEW
    ProjectActionRoutes.kt                     ← NEW
    ServerActionHandler.kt                     ← NEW (build/git/files 화이트리스트)
  ws/
    LogHub.kt                                  ← MOD (seq + ring buffer)
    WsRoutes.kt                                ← MOD (console topic + since=)
  Module.kt                                    ← MOD (와이어링 추가)
  ServerMain.kt                                ← MOD (와이어링 + shutdown hook 추가)

server/src/main/resources/actions/
  build.json                                   ← NEW (build/test/lint)
  git.json                                     ← NEW (status/commit)
  claude.json                                  ← NEW (slash:status 등)

android-app/app/src/main/kotlin/com/siamakerlab/vibecoder/console/
  ui/console/                                  ← NEW package
    ProjectConsoleScreen.kt
    ConsoleViewModel.kt
    ConsoleUiState.kt
    messages/
      AssistantBubble.kt
      ToolUseCard.kt
      ToolResultCard.kt
      ErrorBanner.kt
      SystemNotice.kt
      UnknownEventCard.kt
    input/
      PromptInputBar.kt
      VoiceButton.kt
      QuickActionChips.kt
    scroll/
      AutoScrollState.kt
    status/
      StatusPanel.kt                           ← Phase E
  data/
    remote/
      ConsoleWsClient.kt                       ← NEW
    repository/
      ConsoleRepository.kt                     ← NEW
      ActionsRepository.kt                     ← NEW
      ClaudeStatusRepository.kt                ← NEW (Phase E)
  ui/nav/
    Routes.kt                                  ← MOD (Console route)
    NavGraph.kt                                ← MOD
  ClaudePromptScreen.kt                        ← DELETE (Phase B 종료 시)
  ui/log/LogScreen.kt                          ← DELETE (동시)
android-app/app/src/main/AndroidManifest.xml   ← MOD (RECORD_AUDIO)
android-app/app/src/main/res/values/strings.xml ← MOD (console i18n)
```

### 11.2 구현 순서 (Phase별)

| Phase | 모듈 | 산출물 |
|-------|------|--------|
| **A** | shared DTO + LogHub 확장 + ClaudeSessionManager + StreamParser + ConsoleRoutes + WsRoutes | 서버 단독으로 curl + websocat로 prompt 송신 + JSON 이벤트 수신 가능 |
| **B** | ConsoleWsClient + ConsoleRepository + ProjectConsoleScreen + 메시지 카드 + 기본 PromptInputBar (음성/칩 제외) | 안드로이드에서 실 Claude와 대화 가능 |
| **C** | ProjectAction sealed + Registry + Routes + 기본 manifest 3개 + ServerActionHandler + 안드로이드 QuickActionChips + ActionsRepository | 액션 칩으로 build/git/snippet 실행 |
| **D** | VoiceButton + RECORD_AUDIO 권한 + AutoScrollState + 스크롤 잠금 UI + IME 적응 | 모바일 UX 완성 |
| **E** | ClaudeStatusService + StatusPanel + MCP auto-discovery + 사용자 manifest 핫리로드 | `/status` 데모 + manifest 파일만 추가하면 즉시 칩 노출 |

### 11.3 Session Guide (for `/pdca do --scope`)

| Scope key | 포함 모듈 | 분량 (대략) | 의존성 |
|-----------|-----------|----|--------|
| `phase-A` | shared DTO + LogHub + SessionManager + StreamParser + ConsoleRoutes + WsRoutes + Module 와이어링 | server ~700 lines + shared ~150 lines | 없음 |
| `phase-B` | Android Console UI + Repository + WsClient + 메시지 카드 + PromptInputBar 기본 | android ~900 lines | phase-A |
| `phase-C` | ProjectAction + Registry + Routes + manifest 3개 + ServerActionHandler + Chips UI + ActionsRepository | server ~500 lines + android ~300 lines + JSON ~200 lines | phase-A, phase-B |
| `phase-D` | VoiceButton + AutoScrollState + IME 적응 + 권한 flow | android ~300 lines | phase-B |
| `phase-E` | ClaudeStatusService + StatusPanel + MCP auto-discovery + 사용자 manifest 핫리로드 | server ~250 lines + android ~150 lines | phase-A, phase-C |

권장 세션 분할: 단일 세션에 phase-A 또는 phase-B 또는 (phase-C + phase-D 동시) 처리.

### 11.4 UI Mockup (콘솔 화면)

```
┌──────────────────────────────────────────────┐
│ ← My Awesome App                       ⋮ ▾  │  ← TopAppBar (project name + menu: New session/Build/Git/Files/Artifacts)
├──────────────────────────────────────────────┤
│ ▼ Claude  [/status ↻]  Model: Sonnet 4.6     │  ← StatusPanel (collapsible, Phase E)
│   Plan: Pro  Quota: 87% remaining            │
├──────────────────────────────────────────────┤
│  🤖 Sure, I'll create the gradle wrapper...  │  ← AssistantBubble
│                                              │
│  ┌─🔧 Edit /home/.../build.gradle.kts──────┐ │  ← ToolUseCard (collapsed by default)
│  └──────────────────────────────────────────┘ │
│                                              │
│  ┌─✓ result (123 bytes)──────────────────┐   │  ← ToolResultCard
│  └────────────────────────────────────────┘   │
│                                              │
│  🤖 Done. The wrapper is now in place.       │
│  ─────── ⬇ 3 new messages ─────────────       │  ← jump-to-latest (스크롤 잠금 시)
├──────────────────────────────────────────────┤
│ [Build] [Test] [Lint] [Commit] [/status] >   │  ← QuickActionChips (LazyRow, category tabs above)
├──────────────────────────────────────────────┤
│ ┌──────────────────────────────────┐ [🎤][▶] │  ← PromptInputBar (imePadding)
│ │ Type a message...                │         │
│ └──────────────────────────────────┘         │
└──────────────────────────────────────────────┘
```

---

## 12. Risks (from Plan §5 + technical additions)

| Risk | Mitigation in Design |
|------|----------------------|
| R-01 stream-json 포맷 변동 | `Unknown(raw)` event + 클라이언트 fallback 카드 |
| R-02 프로세스 leak | shutdown hook (ServerMain), idle 30분 SIGTERM, stale PID 검사 |
| R-03 다중 디바이스 충돌 | per-project stdin mutex, 같은 stream broadcast |
| R-04 WS 끊김 이벤트 손실 | seq + ring 200 + ?since= replay, `replay_partial` 정보 이벤트 |
| R-05 RECORD_AUDIO 거부 | 마이크 disabled + 안내. 키보드 100% 사용 가능 |
| R-06 큰 이벤트 frame 초과 | Ktor `maxFrameSize = Long.MAX_VALUE` 이미 설정 |
| R-07 resume 실패 | exit code + stderr 패턴 감지 → session-id 삭제 + 새 spawn |
| R-08 액션 ecosystem 비대 | 카테고리 그룹 + LazyRow + manifest 파일 분리 + `requires` 권한 모델 |
| **R-09 (신규)** manifest user 파일 신뢰 경계 | path-safety, 액션 type whitelist (임의 shell 명령 금지), 길이 제한 |
| **R-10 (신규)** 슬래시 커맨드 stream-json 미수용 가능성 | sidecar one-shot fallback (영속 세션과 분리) |

---

## 13. Next Steps

1. [ ] `/pdca do project-claude-console --scope phase-A` — 서버 세션 매니저 구현
2. [ ] 단위 테스트 (StreamParser, Hub, SessionManager) Phase A 종료 시점에 작성
3. [ ] `/pdca do project-claude-console --scope phase-B` — 안드로이드 콘솔
4. [ ] `/pdca do project-claude-console --scope phase-C` — 액션 시스템
5. [ ] `/pdca do project-claude-console --scope phase-D` — 모바일 UX 폴리시
6. [ ] `/pdca do project-claude-console --scope phase-E` — `/status` + MCP 데모
7. [ ] `/pdca analyze project-claude-console` — gap-detector + design-validator
8. [ ] Match Rate < 90% 시 `/pdca iterate`, ≥90% 시 `/pdca report`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-05-18 | Initial draft. Option C 선택. 5 sub-phases + Session Guide. sealed `ProjectAction` 6 타입 + manifest 시스템. | wody |
