package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.AuditLog
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class AuditLogRow(
    val id: String,
    val ts: String,
    val userId: String?,
    val deviceId: String?,
    val ip: String?,
    val action: String,
    val resourceType: String?,
    val resourceId: String?,
    val result: String,
    val detail: String?,
)

/**
 * v0.15.0 — Audit log 저장소.
 */
class AuditLogRepository(private val clock: Clock) {

    fun insert(
        action: String,
        result: String,
        userId: String? = null,
        deviceId: String? = null,
        ip: String? = null,
        resourceType: String? = null,
        resourceId: String? = null,
        detail: String? = null,
    ): AuditLogRow = transaction {
        val id = Ids.taskId()
        val now = clock.nowIso()
        AuditLog.insert {
            it[AuditLog.id] = id
            it[ts] = now
            it[AuditLog.userId] = userId
            it[AuditLog.deviceId] = deviceId
            it[AuditLog.ip] = ip
            it[AuditLog.action] = action
            it[AuditLog.resourceType] = resourceType
            it[AuditLog.resourceId] = resourceId
            it[AuditLog.result] = result
            it[AuditLog.detail] = detail
        }
        AuditLogRow(id, now, userId, deviceId, ip, action, resourceType, resourceId, result, detail)
    }

    data class Filter(
        val action: String? = null,
        val result: String? = null,
        val userId: String? = null,
        val fromTs: String? = null,
        val toTs: String? = null,
    )

    /** Filter 조건을 AND 로 결합. null 항목은 무시. */
    private fun Filter.toCondition(): Op<Boolean>? {
        val conds = mutableListOf<Op<Boolean>>()
        action?.let { conds += AuditLog.action eq it }
        result?.let { conds += AuditLog.result eq it }
        userId?.let { conds += AuditLog.userId eq it }
        fromTs?.let { conds += AuditLog.ts greaterEq it }
        toTs?.let { conds += AuditLog.ts lessEq it }
        return conds.reduceOrNull { a, b -> a and b }
    }

    fun list(filter: Filter, limit: Int = 200, offset: Long = 0): List<AuditLogRow> = transaction {
        val cond = filter.toCondition()
        val q = if (cond == null) AuditLog.selectAll() else AuditLog.selectAll().where { cond }
        q.orderBy(AuditLog.ts to SortOrder.DESC)
            .limit(limit.coerceIn(1, 1000)).offset(offset.coerceAtLeast(0))
            .map { it.toRow() }
    }

    fun count(filter: Filter): Long = transaction {
        val cond = filter.toCondition()
        val q = if (cond == null) AuditLog.selectAll() else AuditLog.selectAll().where { cond }
        q.count()
    }

    /** Distinct action 값 목록 — UI 의 dropdown 채움에 사용. */
    fun distinctActions(): List<String> = transaction {
        AuditLog.selectAll()
            .map { it[AuditLog.action] }
            .distinct()
            .sorted()
    }

    private fun ResultRow.toRow() = AuditLogRow(
        id = this[AuditLog.id],
        ts = this[AuditLog.ts],
        userId = this[AuditLog.userId],
        deviceId = this[AuditLog.deviceId],
        ip = this[AuditLog.ip],
        action = this[AuditLog.action],
        resourceType = this[AuditLog.resourceType],
        resourceId = this[AuditLog.resourceId],
        result = this[AuditLog.result],
        detail = this[AuditLog.detail],
    )
}
