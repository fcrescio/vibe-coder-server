package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.66.0 — Phase 45. Git commit + (optional) push.
 *
 * v0.18.0 부터 server `GitRoutes.kt` 안에 정의돼 있던 DTO 를 shared SSOT 로 회수.
 * vibe-coder-android v0.7.24+ 가 동일 모양으로 호출.
 *
 * - [message]     : commit message. 빈 문자열 거부.
 * - [push]        : commit 후 origin push 시도. 토큰 / SSH 키 등록돼 있어야 성공.
 *                   default true (모바일 시나리오는 commit 단독 의미 적음).
 * - [onlyTracked] : true 면 `git add -u` (이미 추적 중인 파일 변경분만).
 *                   false 면 `git add -A` (untracked 포함). default false.
 */
@Serializable
data class GitCommitRequestDto(
    val message: String,
    val push: Boolean = true,
    val onlyTracked: Boolean = false,
)

/**
 * v0.66.0 — `POST /api/projects/{id}/git/commit` 응답.
 *
 * - [committed] : 실제로 새 commit 이 생성됐는지 (변경 없으면 false).
 * - [pushed]    : push 옵션 + origin 등록 + 인증 모두 성공해야 true.
 * - [branch]    : 현재 브랜치 이름.
 * - [sha]       : 새 commit SHA (committed=false 면 null).
 * - [log]       : git 명령어들의 raw stdout/stderr (디버그용 — UI 가 collapsible 로 표시 권장).
 */
@Serializable
data class GitCommitResponseDto(
    val committed: Boolean,
    val pushed: Boolean,
    val branch: String,
    val sha: String? = null,
    val log: String = "",
)
