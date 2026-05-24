package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ClaudeUsageSection
import com.siamakerlab.vibecoder.server.notify.Notifiers
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * v0.21.0 — Claude usage 모니터링 + 임계치 이메일 알림.
 *
 * 단일 admin 가정 (CLAUDE.md §1). 보통 모든 프로젝트가 같은 Claude 계정을 공유
 * 하므로 기본적으로 `__scratch__` 프로젝트 하나만 폴링 (config.scratchOnly=true).
 *
 * 알림 정책 (transition 기반):
 *   - usagePercent 가 `warnThresholdPercent` 이상으로 처음 올라간 순간 1회 발송.
 *   - 더 올라가서 `criticalThresholdPercent` 이상에 처음 도달했을 때 1회 발송.
 *   - 다시 아래로 내려가면 (= reset 이후) 마지막 발송 상태 초기화 — 다음 cycle 에서
 *     재발송 가능.
 *
 * 폴링이 실패 (claude /status 호출 실패) 해도 silently skip. 로그만 debug.
 */
class ClaudeUsageMonitor(
    private val statusService: ClaudeStatusService,
    private val notifiers: Notifiers,
    private val configProvider: () -> ClaudeUsageSection,
    private val scratchProjectId: String = "__scratch__",
    /**
     * 추가로 폴링할 프로젝트 id 집합 제공자. scratchOnly=false 일 때만 사용.
     * 기본 빈 리스트 → 모니터는 scratch 만.
     */
    private val activeProjectsProvider: () -> List<String> = { emptyList() },
) {

    /** "warn" / "critical" / null (아직 임계치 미도달 또는 reset 이후). */
    private val lastAlertLevel = AtomicReference<String?>(null)
    /** 마지막 알림 발송 시점. 같은 임계치 transition 직후 재발송을 안전하게 차단. */
    private val lastAlertAt = AtomicReference<Instant?>(null)

    /** 마지막 폴링 결과 (UI 대시보드/콘솔 헤더가 즉시 보여줄 수 있도록 캐시). */
    @Volatile
    private var lastSnapshot: ClaudeStatusDto? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** 가장 최근 snapshot. UI 가 호출. */
    fun snapshot(): ClaudeStatusDto? = lastSnapshot

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "Claude usage monitor started" }
            while (isActive) {
                runCatching { tick() }
                    .onFailure { log.debug(it) { "usage monitor tick failed: ${it.message}" } }
                val cfg = configProvider()
                if (!cfg.enabled) {
                    delay(Duration.ofMinutes(1).toMillis())
                } else {
                    delay(Duration.ofMinutes(cfg.pollIntervalMinutes.toLong().coerceAtLeast(1)).toMillis())
                }
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    private suspend fun tick() {
        val cfg = configProvider()
        if (!cfg.enabled) return

        val ids = mutableListOf<String>()
        ids.add(scratchProjectId)
        if (!cfg.scratchOnly) ids.addAll(activeProjectsProvider())
        // dedup, preserving order
        val unique = LinkedHashSet(ids).toList()

        var maxUsage: Int? = null
        var representative: ClaudeStatusDto? = null
        for (id in unique) {
            val dto = runCatching { statusService.snapshot(id) }.getOrNull() ?: continue
            val u = dto.usagePercent ?: continue
            if (maxUsage == null || u > maxUsage) {
                maxUsage = u
                representative = dto
            }
        }
        if (representative != null) {
            lastSnapshot = representative
        }

        val pct = maxUsage ?: return  // 모든 프로젝트에서 percent 추출 실패 → skip

        // 임계치 transition 판정
        val current = when {
            pct >= cfg.criticalThresholdPercent -> "critical"
            pct >= cfg.warnThresholdPercent -> "warn"
            else -> null
        }
        val prior = lastAlertLevel.get()

        if (current == null) {
            // 임계치 아래로 내려감 → reset
            if (prior != null) {
                log.info { "Claude usage dropped below thresholds ($pct%). Reset alert state." }
                lastAlertLevel.set(null)
                lastAlertAt.set(null)
            }
            return
        }

        // 같은 레벨 이미 알림 보냈으면 skip. 더 강한 레벨로 transition 시 재발송.
        val shouldFire = when {
            prior == null -> true
            prior == "warn" && current == "critical" -> true
            else -> false
        }
        if (!shouldFire) return

        // 너무 잦은 재발송 방지 — 최소 10분 간격.
        val now = Instant.now()
        val last = lastAlertAt.get()
        if (last != null && Duration.between(last, now).toMinutes() < 10) return

        log.info { "Claude usage threshold transition → $current (usage=$pct%). Firing email." }
        // remainingPercent 시그니처에 맞춰 변환 (helper 가 받는 값).
        val remaining = (100 - pct).coerceAtLeast(0)
        notifiers.claudeUsageWarn(remaining, representative?.resetAt)
        lastAlertLevel.set(current)
        lastAlertAt.set(now)
    }
}
