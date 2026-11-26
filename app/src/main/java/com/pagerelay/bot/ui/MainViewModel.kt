package com.pagerelay.bot.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pagerelay.bot.domain.AppSettings
import com.pagerelay.bot.domain.AuditLogEntry
import com.pagerelay.bot.domain.AutomationLiveState
import com.pagerelay.bot.domain.ConversationSessionInfo
import com.pagerelay.bot.domain.EmulatorProfile
import com.pagerelay.bot.domain.GeminiTestResult
import com.pagerelay.bot.domain.LogEventType
import com.pagerelay.bot.domain.PageRelayAppProvider
import com.pagerelay.bot.domain.PageRelayContainer
import com.pagerelay.bot.domain.SavedRecipientConversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LogFilter(val label: String) {
    ALL("All"),
    CALLS("Calls"),
    REPLIES("Replies"),
    ERRORS("Errors")
}

sealed interface GeminiTestState {
    data object Idle : GeminiTestState
    data object Testing : GeminiTestState
    data class Completed(val result: GeminiTestResult) : GeminiTestState
}

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val liveState: AutomationLiveState = AutomationLiveState(),
    val allLogs: List<AuditLogEntry> = emptyList(),
    val filteredLogs: List<AuditLogEntry> = emptyList(),
    val activeSessions: List<ConversationSessionInfo> = emptyList(),
    val selectedLogFilter: LogFilter = LogFilter.ALL,
    val geminiTestState: GeminiTestState = GeminiTestState.Idle,
    val snackbarMessage: String? = null,
    val showEmergencyConfirmDialog: Boolean = false
) {
    val isPageConfigured: Boolean
        get() = settings.targetPageName.isNotBlank() && settings.staticReplyMessage.isNotBlank()

    val isRecipientConfigured: Boolean
        get() = settings.effectiveRecipientName.isNotBlank()

    val totalEventsCount: Int
        get() = allLogs.size

    val callAttemptsCount: Int
        get() = allLogs.count { it.type == LogEventType.CALL_ATTEMPT }

    val errorCount: Int
        get() = allLogs.count { it.isError || it.type == LogEventType.ERROR }

    val readinessCount: Int
        get() = listOf(
            liveState.isNotificationListenerConnected,
            liveState.isAccessibilityServiceConnected,
            isPageConfigured,
            isRecipientConfigured
        ).count { it }

    val canTestMessengerCall: Boolean
        get() = liveState.isAccessibilityServiceConnected &&
            liveState.isMessengerInstalled &&
            isRecipientConfigured &&
            !settings.emergencyStopped &&
            !liveState.isCallInProgress
}

private data class TransientUiState(
    val selectedLogFilter: LogFilter = LogFilter.ALL,
    val geminiTestState: GeminiTestState = GeminiTestState.Idle,
    val snackbarMessage: String? = null,
    val showEmergencyConfirmDialog: Boolean = false
)

class MainViewModel(
    private val container: PageRelayContainer
) : ViewModel() {

    private val settingsStore = container.settingsStore
    private val auditLogStore = container.auditLogStore
    private val automationController = container.automationController

    private val transientState = MutableStateFlow(TransientUiState())

    val uiState: StateFlow<MainUiState> = combine(
        settingsStore.settings,
        automationController.liveState,
        auditLogStore.logs,
        automationController.activeSessions,
        transientState
    ) { settings, liveState, logs, sessions, transient ->
        val filtered = when (transient.selectedLogFilter) {
            LogFilter.ALL -> logs
            LogFilter.CALLS -> logs.filter {
                it.type == LogEventType.CALL_ATTEMPT || it.type == LogEventType.CAPTURE_CONVERSATION
            }
            LogFilter.REPLIES -> logs.filter {
                it.type == LogEventType.INCOMING_MESSAGE ||
                    it.type == LogEventType.STATIC_REPLY ||
                    it.type == LogEventType.GEMINI_REPLY
            }
            LogFilter.ERRORS -> logs.filter {
                it.isError ||
                    it.type == LogEventType.ERROR ||
                    it.type == LogEventType.EMERGENCY_STOP ||
                    (it.callStatus?.isFailure == true)
            }
        }

        MainUiState(
            settings = settings,
            liveState = liveState,
            allLogs = logs,
            filteredLogs = filtered,
            activeSessions = sessions,
            selectedLogFilter = transient.selectedLogFilter,
            geminiTestState = transient.geminiTestState,
            snackbarMessage = transient.snackbarMessage,
            showEmergencyConfirmDialog = transient.showEmergencyConfirmDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        automationController.refreshEnvironmentStatus()
    }

    fun setAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsStore.getCurrentSettings()
            if (enabled && current.emergencyStopped) {
                automationController.resetEmergencyStopAndRearm(enableAutomation = true)
                postSnackbar("Emergency stop cleared — PageRelay is now armed.")
            } else {
                settingsStore.update { it.copy(automationEnabled = enabled) }
                postSnackbar(
                    if (enabled) "PageRelay armed and monitoring notifications."
                    else "PageRelay paused."
                )
            }
        }
    }

    fun requestEmergencyStopDialog() {
        transientState.value = transientState.value.copy(showEmergencyConfirmDialog = true)
    }

    fun dismissEmergencyStopDialog() {
        transientState.value = transientState.value.copy(showEmergencyConfirmDialog = false)
    }

    fun triggerEmergencyStop() {
        transientState.value = transientState.value.copy(showEmergencyConfirmDialog = false)
        automationController.triggerEmergencyStop("User pressed Emergency Stop in PageRelay UI")
        postSnackbar("EMERGENCY STOP activated. All automation halted.")
    }

    fun rearmAfterEmergencyStop(enableAutomation: Boolean) {
        automationController.resetEmergencyStopAndRearm(enableAutomation)
        postSnackbar(
            if (enableAutomation) "PageRelay re-armed and monitoring."
            else "Emergency stop cleared. Toggle Arm when ready."
        )
    }

    fun updateTargetPageName(name: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(targetPageName = name) }
        }
    }

    fun updateStaticReplyMessage(message: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(staticReplyMessage = message) }
        }
    }

    fun setGeminiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(geminiEnabled = enabled) }
        }
    }

    fun updateGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(geminiApiKey = apiKey.trim()) }
            if (transientState.value.geminiTestState !is GeminiTestState.Idle) {
                transientState.value = transientState.value.copy(geminiTestState = GeminiTestState.Idle)
            }
        }
    }

    fun updateGeminiModel(model: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(geminiModel = model) }
        }
    }

    fun updateGeminiSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(geminiSystemPrompt = prompt) }
        }
    }

    fun testGeminiConnection() {
        if (transientState.value.geminiTestState is GeminiTestState.Testing) return
        transientState.value = transientState.value.copy(geminiTestState = GeminiTestState.Testing)
        viewModelScope.launch {
            val result = automationController.testGeminiConnection()
            transientState.value = transientState.value.copy(
                geminiTestState = GeminiTestState.Completed(result)
            )
        }
    }

    fun setAutoCallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(autoCallEnabled = enabled) }
        }
    }

    fun updateSecondaryAccountName(name: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(secondaryAccountName = name) }
        }
    }

    fun startRecipientCapture() {
        automationController.startRecipientCaptureMode()
        postSnackbar("Capture armed — open your secondary account chat in Messenger.")
    }

    fun cancelRecipientCapture() {
        automationController.cancelRecipientCaptureMode()
        postSnackbar("Recipient capture cancelled.")
    }

    fun clearSavedRecipient() {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(savedRecipient = SavedRecipientConversation())
            }
            postSnackbar("Saved recipient signature cleared.")
        }
    }

    fun updateMaxCallDuration(seconds: Int) {
        val clamped = seconds.coerceIn(15, 300)
        viewModelScope.launch {
            settingsStore.update { it.copy(maxCallDurationSeconds = clamped) }
        }
    }

    fun setAutoEndCallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(autoEndCallEnabled = enabled) }
        }
    }

    fun updateDuplicateCooldown(seconds: Int) {
        val clamped = seconds.coerceIn(15, 600)
        viewModelScope.launch {
            settingsStore.update { it.copy(duplicateCallCooldownSeconds = clamped) }
        }
    }

    fun updateEmulatorProfile(profile: EmulatorProfile) {
        viewModelScope.launch {
            settingsStore.update { it.copy(emulatorProfile = profile) }
        }
    }

    fun runTestCall(dryRunOnly: Boolean) {
        val state = uiState.value
        if (state.settings.emergencyStopped) {
            postSnackbar("Clear Emergency Stop before running a Messenger test.")
            return
        }
        if (!state.liveState.isAccessibilityServiceConnected) {
            postSnackbar("Enable the PageRelay Accessibility Service first.")
            return
        }
        if (!state.liveState.isMessengerInstalled) {
            postSnackbar("Official Facebook Messenger (com.facebook.orca) is not installed.")
            return
        }
        if (!state.isRecipientConfigured) {
            postSnackbar("Enter or capture your secondary Messenger account name first.")
            return
        }
        automationController.triggerTestCall(dryRunOnly = dryRunOnly)
        postSnackbar(
            if (dryRunOnly) "Starting Dry-Run verification in Messenger..."
            else "Starting live Messenger audio call test..."
        )
    }

    fun clearConversationSessions() {
        automationController.clearConversationSessions()
        postSnackbar("All tracked conversation sessions cleared.")
    }

    fun resetConversationSession(conversationKey: String) {
        automationController.resetConversationSession(conversationKey)
        postSnackbar("Conversation session reset — next message will trigger initial flow.")
    }

    fun setLogFilter(filter: LogFilter) {
        transientState.value = transientState.value.copy(selectedLogFilter = filter)
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            auditLogStore.clearAll()
            postSnackbar("Activity logs cleared.")
        }
    }

    fun exportLogs(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val summary = automationController.exportLogsSummary()
            onReady(summary)
        }
    }

    fun refreshEnvironmentStatus() {
        automationController.refreshEnvironmentStatus()
    }

    fun showSnackbar(message: String) {
        postSnackbar(message)
    }

    fun consumeSnackbar() {
        transientState.value = transientState.value.copy(snackbarMessage = null)
    }

    private fun postSnackbar(message: String) {
        transientState.value = transientState.value.copy(snackbarMessage = message)
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val provider = application as? PageRelayAppProvider
                        ?: error("Application must implement PageRelayAppProvider")
                    return MainViewModel(provider.container) as T
                }
            }
    }
}
