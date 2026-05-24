package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.AdminUsers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class AdminUserRow(
    val id: String,
    val username: String,
    val passwordHash: String,
    val createdAt: String,
    val lastLoginAt: String?,
    val passwordChangedAt: String,
    /** v0.26.0 — TOTP. null = 2FA 비활성. */
    val totpSecret: String? = null,
    val totpEnabledAt: String? = null,
) {
    val totpEnabled: Boolean get() = !totpSecret.isNullOrBlank()
}

/**
 * 단일 admin 사용자 저장소.
 *
 * 1인 LAN 도구 전제이므로 사실상 0~1행만 존재한다. 다중 사용자 지원은
 * docs/01-plan/admin-web.md §11에 따라 명시적 비범위.
 */
class AdminUserRepository(private val clock: Clock) {

    fun count(): Long = transaction {
        AdminUsers.selectAll().count()
    }

    fun findByUsername(username: String): AdminUserRow? = transaction {
        AdminUsers.selectAll()
            .where { AdminUsers.username eq username }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun findById(id: String): AdminUserRow? = transaction {
        AdminUsers.selectAll()
            .where { AdminUsers.id eq id }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun insert(id: String, username: String, passwordHash: String): AdminUserRow = transaction {
        val now = clock.nowIso()
        AdminUsers.insert {
            it[AdminUsers.id] = id
            it[AdminUsers.username] = username
            it[AdminUsers.passwordHash] = passwordHash
            it[createdAt] = now
            it[passwordChangedAt] = now
        }
        AdminUserRow(id, username, passwordHash, now, null, now)
    }

    fun touchLogin(id: String) = transaction {
        AdminUsers.update({ AdminUsers.id eq id }) { it[lastLoginAt] = clock.nowIso() }
    }

    fun updatePassword(id: String, newHash: String) = transaction {
        val now = clock.nowIso()
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[passwordHash] = newHash
            it[passwordChangedAt] = now
        }
    }

    /** v0.26.0 — TOTP 활성화. secret 은 이미 generateSecret() 으로 생성된 Base32. */
    fun enableTotp(id: String, base32Secret: String) = transaction {
        val now = clock.nowIso()
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[totpSecret] = base32Secret
            it[totpEnabledAt] = now
        }
    }

    /** v0.26.0 — TOTP 비활성화. */
    fun disableTotp(id: String) = transaction {
        AdminUsers.update({ AdminUsers.id eq id }) {
            it[totpSecret] = null
            it[totpEnabledAt] = null
        }
    }

    private fun ResultRow.toRow() = AdminUserRow(
        id = this[AdminUsers.id],
        username = this[AdminUsers.username],
        passwordHash = this[AdminUsers.passwordHash],
        createdAt = this[AdminUsers.createdAt],
        lastLoginAt = this[AdminUsers.lastLoginAt],
        passwordChangedAt = this[AdminUsers.passwordChangedAt],
        totpSecret = this[AdminUsers.totpSecret],
        totpEnabledAt = this[AdminUsers.totpEnabledAt],
    )
}
