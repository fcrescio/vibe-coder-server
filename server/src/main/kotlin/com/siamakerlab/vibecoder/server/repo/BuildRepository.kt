package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Builds
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class BuildRow(
    val id: String,
    val projectId: String,
    val variant: String,
    val status: TaskStatus,
    val logPath: String?,
    val artifactId: String?,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val createdAt: String,
)

class BuildRepository(private val clock: Clock) {

    fun create(id: String, projectId: String, variant: String, logPath: String): BuildRow = transaction {
        val now = clock.nowIso()
        Builds.insert {
            it[Builds.id] = id
            it[Builds.projectId] = projectId
            it[Builds.variant] = variant
            it[status] = TaskStatus.PENDING.name
            it[Builds.logPath] = logPath
            it[createdAt] = now
        }
        BuildRow(id, projectId, variant, TaskStatus.PENDING, logPath, null, null, null, null, now)
    }

    fun setStatus(id: String, status: TaskStatus, errorMessage: String? = null) = transaction {
        val now = clock.nowIso()
        Builds.update({ Builds.id eq id }) {
            it[Builds.status] = status.name
            when (status) {
                TaskStatus.RUNNING -> it[startedAt] = now
                TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED, TaskStatus.TIMEOUT -> {
                    it[finishedAt] = now
                    if (errorMessage != null) it[Builds.errorMessage] = errorMessage
                }
                else -> Unit
            }
        }
    }

    fun attachArtifact(id: String, artifactId: String) = transaction {
        Builds.update({ Builds.id eq id }) { it[Builds.artifactId] = artifactId }
    }

    /** Null out [Builds.artifactId] for every build that points at [artifactId]. */
    fun detachArtifact(artifactId: String) = transaction {
        Builds.update({ Builds.artifactId eq artifactId }) { it[Builds.artifactId] = null }
    }

    fun get(id: String): BuildRow? = transaction {
        Builds.selectAll().where { Builds.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 50): List<BuildRow> = transaction {
        Builds.selectAll().where { Builds.projectId eq projectId }
            .orderBy(Builds.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun lastForProject(projectId: String): BuildRow? = listForProject(projectId, 1).firstOrNull()

    /**
     * v0.58.0 — Phase 37 빌드 결과 비교의 "이전" 후보.
     * 같은 projectId 의 SUCCEEDED 빌드 중 [beforeCreatedAt] 보다 createdAt 가 strictly
     * 이전인 가장 최근 row. null = 같은 프로젝트에 이전 성공 빌드 없음 (첫 성공 빌드).
     */
    fun previousSuccessfulBefore(projectId: String, beforeCreatedAt: String): BuildRow? = transaction {
        Builds.selectAll().where {
            (Builds.projectId eq projectId) and
                (Builds.status eq TaskStatus.SUCCESS.name) and
                (Builds.createdAt less beforeCreatedAt)
        }
            .orderBy(Builds.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it.toRow() }
            .singleOrNull()
    }

    /** ProjectService.delete cascade — 모든 build row 일괄 제거. */
    fun deleteForProject(projectId: String): Int = transaction {
        Builds.deleteWhere { Builds.projectId eq projectId }
    }

    /** Number of builds currently in PENDING or RUNNING state across the whole server. */
    fun countRunning(): Int = transaction {
        Builds.selectAll()
            .where { (Builds.status eq TaskStatus.RUNNING.name) or (Builds.status eq TaskStatus.PENDING.name) }
            .count()
            .toInt()
    }

    private fun ResultRow.toRow() = BuildRow(
        id = this[Builds.id],
        projectId = this[Builds.projectId],
        variant = this[Builds.variant],
        status = TaskStatus.valueOf(this[Builds.status]),
        logPath = this[Builds.logPath],
        artifactId = this[Builds.artifactId],
        errorMessage = this[Builds.errorMessage],
        startedAt = this[Builds.startedAt],
        finishedAt = this[Builds.finishedAt],
        createdAt = this[Builds.createdAt],
    )
}
