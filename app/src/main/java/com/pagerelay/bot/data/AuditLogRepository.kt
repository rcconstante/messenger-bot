package com.pagerelay.bot.data

import com.pagerelay.bot.domain.AuditLogEntry
import com.pagerelay.bot.domain.AuditLogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AuditLogRepository(
    private val dao: AuditLogDao
) : AuditLogStore {

    override val logs: Flow<List<AuditLogEntry>> = dao.observeRecent(DEFAULT_OBSERVE_LIMIT)
        .map { entities -> entities.map { it.toDomain() } }

    override suspend fun insert(entry: AuditLogEntry): Long {
        val insertedId = dao.insert(entry.toEntity())
        dao.pruneOlderThan(MAX_STORED_LOGS)
        return insertedId
    }

    override suspend fun clearAll() {
        dao.clearAll()
    }

    override suspend fun getRecentSnapshot(limit: Int): List<AuditLogEntry> {
        val safeLimit = limit.coerceIn(1, MAX_STORED_LOGS)
        return dao.getRecentSnapshot(safeLimit).map { it.toDomain() }
    }

    companion object {
        private const val DEFAULT_OBSERVE_LIMIT = 250
        private const val MAX_STORED_LOGS = 500
    }
}
