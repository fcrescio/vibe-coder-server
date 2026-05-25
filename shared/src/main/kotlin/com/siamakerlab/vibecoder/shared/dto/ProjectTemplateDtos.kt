package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.66.0 — Phase 45. 신규 프로젝트 생성 시 사용할 미리 정의된 starter 템플릿.
 *
 * 서버 `ProjectTemplates` 카탈로그 (id / title / description / starterPrompt) 의
 * UI-노출 분 (starterPrompt 본문은 응답에 포함, 사용자가 dropdown 에서 선택 후
 * `RegisterProjectRequestDto.templateId` 로 전달).
 *
 * 기본 카탈로그 (`empty` / `compose-basic` / `compose-mvvm-hilt` /
 * `compose-mvvm-room` / `wear-os` / `android-tv`) — server 코드 기준 6개.
 * 카탈로그는 서버 측 상수라 응답이 매우 정적 (캐싱 가능).
 */
@Serializable
data class ProjectTemplateDto(
    val id: String,
    val title: String,
    val description: String,
    /**
     * Claude 콘솔에 자동 입력될 starter prompt. UI 미리보기에 사용해도 됨.
     * id="empty" 는 빈 문자열.
     */
    val starterPrompt: String,
)

/**
 * `GET /api/project-templates` 응답.
 */
@Serializable
data class ProjectTemplatesResponseDto(
    val templates: List<ProjectTemplateDto> = emptyList(),
)
