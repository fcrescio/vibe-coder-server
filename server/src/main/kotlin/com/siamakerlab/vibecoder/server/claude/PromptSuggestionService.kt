package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.db.ConversationTurns
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * v0.31.0 — 콘솔 prompt 자동완성 추천.
 *
 * 단순 접근:
 *   - 같은 프로젝트의 user role turn 중 prefix 매치 후보 수집 (LIKE).
 *   - 최근 사용 우선 (ts DESC), 중복 제거.
 *   - 짧은 in-memory cache (60 s) 로 매 키 입력마다 DB hit 방지.
 *
 * 단점:
 *   - 단어 단위 n-gram / 부분 매치 / fuzzy 미지원 — prefix only.
 *   - 큰 history (수만 turn) 면 LIKE 가 느려질 수 있음. v0.30.0 의 글로벌 검색
 *     처럼 PostgreSQL FTS 로 교체 검토.
 */
class PromptSuggestionService {

    private data class Cached(val expiresAt: Instant, val values: List<String>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val ttl = Duration.ofSeconds(60)

    fun suggest(projectId: String, prefix: String, limit: Int = 8): List<String> {
        if (prefix.length < 2) return emptyList()
        val key = "$projectId|${prefix.lowercase()}"
        val now = Instant.now()
        cache[key]?.let { if (it.expiresAt.isAfter(now)) return it.values.take(limit) }

        val safePrefix = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = runCatching {
            transaction {
                ConversationTurns.selectAll()
                    .where { (ConversationTurns.projectId eq projectId) and
                        (ConversationTurns.role eq "user") and
                        (ConversationTurns.content like "$safePrefix%") }
                    .orderBy(ConversationTurns.ts to SortOrder.DESC)
                    .limit(50)
                    .map { it[ConversationTurns.content] }
            }
        }.getOrElse {
            log.debug(it) { "prompt suggest query failed" }
            return emptyList()
        }

        // 중복 제거 + 짧은 (10자 미만) prompt 는 제외 (의미 없는 "ok" / "yes" 등)
        val deduped = LinkedHashSet<String>()
        for (r in rows) {
            val trimmed = r.trim()
            if (trimmed.length < 10) continue
            // 첫 줄만 (long prompt 의 후반 부분이 별도 entry 로 잡히는 걸 방지)
            val firstLine = trimmed.lineSequence().first().take(200)
            deduped += firstLine
            if (deduped.size >= limit * 2) break
        }
        val result = deduped.take(limit)
        cache[key] = Cached(now.plus(ttl), result)
        return result
    }
}
