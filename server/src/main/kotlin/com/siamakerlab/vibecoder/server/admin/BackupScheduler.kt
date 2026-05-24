package com.siamakerlab.vibecoder.server.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}

/**
 * v0.60.0 — Phase 39 backup scheduler.
 *
 * 1분 polling. config 의 backup.cron 이 현재 시각과 매치하면 [BackupService.createScheduled]
 * 호출 후 [BackupService.deleteOldestOverRetention] 으로 rotation.
 *
 * cron 형식은 [com.siamakerlab.vibecoder.server.build.BuildScheduler] 와 동일 — "HH:MM",
 * "*:MM", "*:*". 단일 사용자 환경에선 보통 매일 한 번 (예: "03:00").
 *
 * Dedupe: lastFiredMinute 가 이번 tick 과 같으면 skip (한 분에 최대 1회).
 */
class BackupScheduler(
    private val service: BackupService,
    private val cronProvider: () -> String,
    private val retentionProvider: () -> Int,
    private val enabledProvider: () -> Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    @Volatile private var lastFiredMinute: String? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "BackupScheduler started (zone=$zoneId)" }
            while (isActive) {
                runCatching { tick() }.onFailure { log.warn(it) { "backup scheduler tick failed" } }
                delay(60_000)
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    /** Public for tests. */
    fun tick(now: ZonedDateTime = ZonedDateTime.now(zoneId)) {
        if (!enabledProvider()) return
        val cron = cronProvider()
        val hour = now.hour
        val minute = now.minute
        if (!matches(cron, hour, minute)) return
        val minuteKey = "%04d-%02d-%02d-%02d:%02d".format(now.year, now.monthValue, now.dayOfMonth, hour, minute)
        if (lastFiredMinute == minuteKey) return
        lastFiredMinute = minuteKey
        runCatching {
            service.createScheduled(now.toInstant())
            service.deleteOldestOverRetention(retentionProvider())
        }.onFailure { e ->
            log.warn(e) { "scheduled backup failed: ${e.message}" }
        }
    }

    private fun matches(cron: String, hour: Int, minute: Int): Boolean {
        val parts = cron.trim().split(":")
        if (parts.size != 2) return false
        val hPart = parts[0]
        val mPart = parts[1]
        val hourOk = hPart == "*" || hPart.toIntOrNull() == hour
        val minOk = mPart == "*" || mPart.toIntOrNull() == minute
        return hourOk && minOk
    }
}
