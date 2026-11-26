package com.pagerelay.bot.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun getCurrentSettings(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface AuditLogStore {
    val logs: Flow<List<AuditLogEntry>>
    suspend fun insert(entry: AuditLogEntry): Long
    suspend fun clearAll()
    suspend fun getRecentSnapshot(limit: Int = 100): List<AuditLogEntry>
}

interface AutomationController {
    val liveState: StateFlow<AutomationLiveState>
    val activeSessions: StateFlow<List<ConversationSessionInfo>>

    fun refreshEnvironmentStatus()
    fun triggerTestCall(dryRunOnly: Boolean)
    fun startRecipientCaptureMode()
    fun cancelRecipientCaptureMode()
    fun triggerEmergencyStop(reason: String = "User pressed Emergency Stop")
    fun resetEmergencyStopAndRearm(enableAutomation: Boolean = false)
    fun clearConversationSessions()
    fun resetConversationSession(conversationKey: String)
    suspend fun testGeminiConnection(): GeminiTestResult
    suspend fun exportLogsSummary(): String
}

interface PageRelayContainer {
    val settingsStore: SettingsStore
    val auditLogStore: AuditLogStore
    val automationController: AutomationController
}

interface PageRelayAppProvider {
    val container: PageRelayContainer
}

