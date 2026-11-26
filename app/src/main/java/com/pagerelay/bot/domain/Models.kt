package com.pagerelay.bot.domain

enum class EmulatorProfile(
    val displayName: String,
    val description: String,
    val stepDelayMs: Long,
    val searchSettleMs: Long,
    val launchWaitMs: Long,
    val nodePollTimeoutMs: Long
) {
    LD_PLAYER(
        displayName = "LDPlayer",
        description = "Extended settle delays tuned for LDPlayer virtualized rendering",
        stepDelayMs = 950L,
        searchSettleMs = 1500L,
        launchWaitMs = 2800L,
        nodePollTimeoutMs = 9000L
    ),
    ANDROID_STUDIO_EMULATOR(
        displayName = "Android Studio Emulator",
        description = "Balanced timing profile for AVD x86_64 / ARM64 images",
        stepDelayMs = 800L,
        searchSettleMs = 1250L,
        launchWaitMs = 2400L,
        nodePollTimeoutMs = 8000L
    ),
    PHYSICAL_DEVICE(
        displayName = "Physical Android Device",
        description = "Faster transition delays for native hardware",
        stepDelayMs = 500L,
        searchSettleMs = 900L,
        launchWaitMs = 1800L,
        nodePollTimeoutMs = 6500L
    );

    companion object {
        fun fromName(name: String?): EmulatorProfile =
            entries.firstOrNull { it.name == name } ?: LD_PLAYER
    }
}

data class SavedRecipientConversation(
    val displayName: String = "",
    val secondaryLabel: String = "",
    val conversationSignature: String = "",
    val capturedAtMillis: Long = 0L
) {
    val isCaptured: Boolean
        get() = displayName.isNotBlank() && capturedAtMillis > 0L
}

data class AppSettings(
    val automationEnabled: Boolean = false,
    val emergencyStopped: Boolean = false,
    val targetPageName: String = "",
    val staticReplyMessage: String = "Thanks for messaging us! Our team has been notified and is reviewing your inquiry right away.",
    val geminiEnabled: Boolean = false,
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val geminiSystemPrompt: String = "You are a polite, concise assistant replying on behalf of a Facebook Page. Answer clearly in 1-3 short sentences.",
    val autoCallEnabled: Boolean = true,
    val secondaryAccountName: String = "",
    val savedRecipient: SavedRecipientConversation = SavedRecipientConversation(),
    val maxCallDurationSeconds: Int = 60,
    val autoEndCallEnabled: Boolean = true,
    val duplicateCallCooldownSeconds: Int = 90,
    val emulatorProfile: EmulatorProfile = EmulatorProfile.LD_PLAYER
) {
    val effectiveRecipientName: String
        get() = savedRecipient.displayName.ifBlank { secondaryAccountName }.trim()

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
        val SUPPORTED_GEMINI_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite"
        )
        val DEPRECATED_GEMINI_MODELS = setOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash"
        )
    }
}

/**
 * Strictly observable statuses for experimental Messenger UI automation.
 * Never claims a call connected unless active call-timer UI is explicitly observed.
 */
enum class CallObservableStatus(
    val label: String,
    val isTerminal: Boolean = false,
    val isFailure: Boolean = false,
    val isVerifiedActive: Boolean = false
) {
    QUEUED("Queued"),
    LAUNCHING_MESSENGER("Launching Messenger"),
    NAVIGATING_CONVERSATION("Locating Recipient Conversation"),
    VERIFYING_RECIPIENT("Verifying Recipient Identity"),
    INITIATING_AUDIO_CALL("Initiating Audio Call"),
    CALL_INITIATED_UNVERIFIED("Audio Call Tapped (Connection Unverified)"),
    RINGING_OBSERVED("Ringing / Calling UI Observed"),
    ACTIVE_CALL_TIMER_OBSERVED("Active Call Timer Verified", isVerifiedActive = true),
    ENDED_AUTOMATICALLY("Auto-Ended After Max Duration", isTerminal = true),
    COMPLETED_HANDOFF("Audio Call Handed Off (Auto-End Disabled)", isTerminal = true),
    DRY_RUN_VERIFIED("Dry-Run Recipient & Audio Button Verified", isTerminal = true),
    SKIPPED_DISABLED("Skipped (Auto-Call Disabled)", isTerminal = true),
    SKIPPED_DUPLICATE("Skipped (Duplicate Call Guard)", isTerminal = true),
    FAILED_SERVICE_UNAVAILABLE("Accessibility Service Not Authorized", isTerminal = true, isFailure = true),
    FAILED_MESSENGER_NOT_INSTALLED("Facebook Messenger Not Installed", isTerminal = true, isFailure = true),
    FAILED_IDENTITY_MISMATCH("Aborted: Recipient Identity Mismatch", isTerminal = true, isFailure = true),
    FAILED_UI_NOT_FOUND("Aborted: Messenger UI Element Not Found", isTerminal = true, isFailure = true),
    FAILED_TIMEOUT("Aborted: Messenger UI Timed Out", isTerminal = true, isFailure = true),
    EMERGENCY_STOPPED("Aborted: Emergency Stop Activated", isTerminal = true, isFailure = true),
    ERROR("Automation Error", isTerminal = true, isFailure = true);

    companion object {
        fun fromName(name: String?): CallObservableStatus? =
            entries.firstOrNull { it.name == name }
    }
}

enum class LogEventType(val label: String) {
    INCOMING_MESSAGE("Incoming Message"),
    STATIC_REPLY("Static Reply"),
    GEMINI_REPLY("Gemini Reply"),
    CALL_ATTEMPT("Messenger Call"),
    CAPTURE_CONVERSATION("Recipient Capture"),
    EMERGENCY_STOP("Emergency Stop"),
    SYSTEM_INFO("System"),
    ERROR("Error");

    companion object {
        fun fromName(name: String?): LogEventType =
            entries.firstOrNull { it.name == name } ?: SYSTEM_INFO
    }
}

data class AuditLogEntry(
    val id: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
    val type: LogEventType,
    val title: String,
    val summary: String,
    val recipientName: String? = null,
    val callStatus: CallObservableStatus? = null,
    val durationSeconds: Int? = null,
    val diagnosticDetails: String? = null,
    val isError: Boolean = false
)

enum class PipelineStepState {
    IDLE,
    ACTIVE,
    COMPLETED,
    WARNING,
    FAILED
}

data class PipelineStepDescriptor(
    val number: Int,
    val title: String,
    val subtitle: String
)

object ExecutionFlowSpec {
    val steps: List<PipelineStepDescriptor> = listOf(
        PipelineStepDescriptor(1, "Detect Page Message", "Watch notifications for target Facebook Page"),
        PipelineStepDescriptor(2, "Send Static Reply", "Instant inline reply without Facebook login"),
        PipelineStepDescriptor(3, "Open Messenger", "Launch official Facebook Messenger app"),
        PipelineStepDescriptor(4, "Locate Conversation", "Navigate to configured secondary account"),
        PipelineStepDescriptor(5, "Verify Identity", "Confirm chat header matches saved recipient"),
        PipelineStepDescriptor(6, "Start Audio Call", "Tap accessible audio-only call control"),
        PipelineStepDescriptor(7, "Record Observable Status", "Log verifiable UI state & auto-end timer"),
        PipelineStepDescriptor(8, "Keep Monitoring", "Non-blocking queue continues watching alerts"),
        PipelineStepDescriptor(9, "Gemini Follow-Up", "AI replies to subsequent messages if enabled")
    )
}

data class AutomationLiveState(
    val isNotificationListenerConnected: Boolean = false,
    val isAccessibilityServiceConnected: Boolean = false,
    val isMessengerInstalled: Boolean = false,
    val isCallInProgress: Boolean = false,
    val isCaptureModeActive: Boolean = false,
    val currentCallStatus: CallObservableStatus? = null,
    val currentCallElapsedSeconds: Int = 0,
    val currentStepMessage: String = "Standing by — waiting for configuration",
    val lastTriggeredPage: String? = null,
    val lastTriggeredAtMillis: Long? = null,
    val activeSessionCount: Int = 0,
    val pipelineSteps: List<PipelineStepState> = List(9) { PipelineStepState.IDLE }
)

data class ConversationSessionInfo(
    val conversationKey: String,
    val senderTitle: String,
    val messageCount: Int,
    val initialStaticReplySent: Boolean,
    val autoCallTriggered: Boolean,
    val lastMessagePreview: String,
    val lastUpdatedMillis: Long,
    val openingMessagePreview: String = "",
    val contextTurnCount: Int = 0
)

data class CallRequest(
    val requestId: String,
    val triggerSource: String,
    val secondaryAccountName: String,
    val savedRecipient: SavedRecipientConversation,
    val maxDurationSeconds: Int,
    val autoEndCall: Boolean,
    val dryRunOnly: Boolean = false,
    val emulatorProfile: EmulatorProfile = EmulatorProfile.LD_PLAYER,
    val requestedAtMillis: Long = System.currentTimeMillis()
)

data class GeminiTestResult(
    val success: Boolean,
    val message: String
)
