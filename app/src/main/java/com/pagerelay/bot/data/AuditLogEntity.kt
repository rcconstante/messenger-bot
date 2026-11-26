package com.pagerelay.bot.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pagerelay.bot.domain.AuditLogEntry
import com.pagerelay.bot.domain.CallObservableStatus
import com.pagerelay.bot.domain.LogEventType

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampMillis: Long,
    val type: String,
    val title: String,
    val summary: String,
    val recipientName: String?,
    val callStatus: String?,
    val durationSeconds: Int?,
    val diagnosticDetails: String?,
    val isError: Boolean
)

fun AuditLogEntity.toDomain(): AuditLogEntry = AuditLogEntry(
    id = id,
    timestampMillis = timestampMillis,
    type = LogEventType.fromName(type),
    title = title,
    summary = summary,
    recipientName = recipientName,
    callStatus = CallObservableStatus.fromName(callStatus),
    durationSeconds = durationSeconds,
    diagnosticDetails = diagnosticDetails,
    isError = isError
)

fun AuditLogEntry.toEntity(): AuditLogEntity = AuditLogEntity(
    id = id,
    timestampMillis = timestampMillis,
    type = type.name,
    title = title,
    summary = summary,
    recipientName = recipientName,
    callStatus = callStatus?.name,
    durationSeconds = durationSeconds,
    diagnosticDetails = diagnosticDetails,
    isError = isError
)
