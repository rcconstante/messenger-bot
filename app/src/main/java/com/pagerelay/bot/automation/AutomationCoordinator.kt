package com.pagerelay.bot.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pagerelay.bot.ai.GeminiReplyClient
import com.pagerelay.bot.domain.AppSettings
import com.pagerelay.bot.domain.AuditLogEntry
import com.pagerelay.bot.domain.AuditLogStore
import com.pagerelay.bot.domain.AutomationController
import com.pagerelay.bot.domain.AutomationLiveState
import com.pagerelay.bot.domain.CallObservableStatus
import com.pagerelay.bot.domain.CallRequest
import com.pagerelay.bot.domain.ConversationSessionInfo
import com.pagerelay.bot.domain.GeminiTestResult
import com.pagerelay.bot.domain.LogEventType
import com.pagerelay.bot.domain.PipelineStepState
import com.pagerelay.bot.domain.SavedRecipientConversation
import com.pagerelay.bot.domain.SettingsStore
import com.pagerelay.bot.service.PageNotificationListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AutomationCoordinator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val auditLogStore: AuditLogStore,
    private val geminiClient: GeminiReplyClient,
    private val sessionManager: ConversationSessionManager = ConversationSessionManager(),
    private val callGuard: CallGuard = CallGuard(),
    private val notificationHelper: EmergencyStopNotificationHelper = EmergencyStopNotificationHelper(context)
) : AutomationController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callQueue = Channel<CallRequest>(capacity = Channel.BUFFERED)
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var activeCallJob: Job? = null

    private val emergencyStopFlag = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)

    private val _liveState = MutableStateFlow(AutomationLiveState())
    override val liveState: StateFlow<AutomationLiveState> = _liveState.asStateFlow()

    override val activeSessions: StateFlow<List<ConversationSessionInfo>> = sessionManager.sessions

    init {
        notificationHelper.ensureChannel()

        AccessibilityServiceBridge.onServiceConnectionChanged = { connected ->
            _liveState.update { it.copy(isAccessibilityServiceConnected = connected) }
        }

        AccessibilityServiceBridge.onMessengerWindowUpdated = { tree ->
            handleMessengerWindowForCapture(tree)
        }

        scope.launch {
            sessionManager.sessions.collect { list ->
                _liveState.update { it.copy(activeSessionCount = list.size) }
            }
        }

        scope.launch {
            settingsStore.settings.collect { settings ->
                emergencyStopFlag.set(settings.emergencyStopped)
                if (settings.emergencyStopped) {
                    _liveState.update { state ->
                        if (state.isCallInProgress) state else state.copy(
                            currentStepMessage = "Emergency Stop active — reset to resume automation"
                        )
                    }
                }
            }
        }

        // Dedicated sequential call worker; never blocks incoming notification processing.
        scope.launch {
            for (request in callQueue) {
                if (emergencyStopFlag.get()) {
                    recordCallOutcome(
                        request = request,
                        status = CallObservableStatus.EMERGENCY_STOPPED,
                        summary = "Skipped queued call because Emergency Stop is active."
                    )
                    continue
                }
                processCallRequest(request)
            }
        }

        refreshEnvironmentStatus()
    }

    fun onNotificationListenerConnectionChanged(connected: Boolean) {
        _liveState.update { it.copy(isNotificationListenerConnected = connected) }
    }

    fun onIncomingNotificationMessage(message: IncomingPageMessage) {
        scope.launch {
            val settings = settingsStore.getCurrentSettings()
            if (settings.emergencyStopped || emergencyStopFlag.get()) {
                return@launch
            }
            if (!settings.automationEnabled) {
                return@launch
            }
            if (!matchesTargetPage(message, settings.targetPageName)) {
                return@launch
            }

            val decision = sessionManager.onIncomingMessage(
                conversationKey = message.conversationKey,
                senderTitle = message.senderName,
                messageText = message.messageText,
                notificationHistory = message.notificationHistory
            )

            if (decision.action == MessageAction.IGNORE_DUPLICATE) {
                return@launch
            }

            val pageDisplay = settings.targetPageName.trim().ifBlank {
                message.conversationTitle?.takeIf { it.isNotBlank() } ?: message.title
            }

            _liveState.update { state ->
                state.copy(
                    lastTriggeredPage = pageDisplay,
                    lastTriggeredAtMillis = message.receivedAtMillis,
                    currentStepMessage = "Detected message from ${message.senderName}",
                    pipelineSteps = updateSteps(
                        state.pipelineSteps,
                        STEP_DETECT to PipelineStepState.COMPLETED,
                        STEP_MONITOR to PipelineStepState.ACTIVE
                    )
                )
            }

            logEvent(
                type = LogEventType.INCOMING_MESSAGE,
                title = "Incoming Page Message",
                summary = "From ${message.senderName} (${message.packageName}): \"${message.messageText.take(120)}\""
            )

            // Serialize per-conversation reply execution so static reply / prior Gemini replies
            // are recorded before the 2nd, 3rd, or burst follow-up re-reads the full conversation.
            val conversationMutex = conversationLocks.computeIfAbsent(message.conversationKey) { Mutex() }
            conversationMutex.withLock {
                val latestSettings = settingsStore.getCurrentSettings()
                if (latestSettings.emergencyStopped || emergencyStopFlag.get() || !latestSettings.automationEnabled) {
                    return@withLock
                }

                when (decision.action) {
                    MessageAction.FIRST_MESSAGE_STATIC_AND_CALL -> {
                        handleFirstSessionMessage(message, latestSettings)
                    }

                    MessageAction.FOLLOWUP_GEMINI -> {
                        handleFollowUpMessage(message, latestSettings, decision)
                    }

                    MessageAction.IGNORE_DUPLICATE -> Unit
                }
            }
        }
    }

    private suspend fun handleFirstSessionMessage(
        message: IncomingPageMessage,
        settings: AppSettings
    ) {
        // Step 2: Immediate static inline reply (no Facebook credentials)
        setStepState(STEP_STATIC_REPLY, PipelineStepState.ACTIVE, "Sending predefined static reply...")
        val staticReply = settings.staticReplyMessage.trim()
        if (staticReply.isBlank()) {
            setStepState(STEP_STATIC_REPLY, PipelineStepState.WARNING, "Static reply text is blank; skipped reply.")
        } else {
            val replyResult = message.replyAction(staticReply)
            replyResult.fold(
                onSuccess = {
                    sessionManager.recordAssistantReply(message.conversationKey, staticReply)
                    setStepState(
                        STEP_STATIC_REPLY,
                        PipelineStepState.COMPLETED,
                        "Static reply sent to ${message.senderName}"
                    )
                    logEvent(
                        type = LogEventType.STATIC_REPLY,
                        title = "Static Reply Sent",
                        summary = "Replied inline to ${message.senderName}: \"${staticReply.take(120)}\""
                    )
                },
                onFailure = { error ->
                    setStepState(
                        STEP_STATIC_REPLY,
                        PipelineStepState.FAILED,
                        "Static reply failed: ${error.message}"
                    )
                    logEvent(
                        type = LogEventType.ERROR,
                        title = "Static Reply Failed",
                        summary = "Could not send inline reply to ${message.senderName}: ${error.message}",
                        isError = true
                    )
                }
            )
        }

        // Step 8 stays active while call runs asynchronously on callQueue.
        setStepState(STEP_MONITOR, PipelineStepState.ACTIVE, null)

        if (!settings.autoCallEnabled) {
            logEvent(
                type = LogEventType.CALL_ATTEMPT,
                title = "Auto-Call Skipped",
                summary = "Auto-call is disabled in settings.",
                recipientName = settings.effectiveRecipientName.ifBlank { null },
                callStatus = CallObservableStatus.SKIPPED_DISABLED
            )
            return
        }

        val targetRecipient = settings.effectiveRecipientName
        if (targetRecipient.isBlank()) {
            logEvent(
                type = LogEventType.CALL_ATTEMPT,
                title = "Auto-Call Skipped",
                summary = "No secondary Messenger recipient name configured.",
                callStatus = CallObservableStatus.FAILED_IDENTITY_MISMATCH,
                isError = true
            )
            return
        }

        val request = CallRequest(
            requestId = "msg-${UUID.randomUUID().toString().take(8)}",
            triggerSource = "Page message from ${message.senderName}",
            secondaryAccountName = settings.secondaryAccountName,
            savedRecipient = settings.savedRecipient,
            maxDurationSeconds = settings.maxCallDurationSeconds,
            autoEndCall = settings.autoEndCallEnabled,
            dryRunOnly = false,
            emulatorProfile = settings.emulatorProfile
        )

        val enqueued = callQueue.trySend(request).isSuccess
        if (enqueued) {
            sessionManager.markAutoCallTriggered(message.conversationKey)
        } else {
            logEvent(
                type = LogEventType.ERROR,
                title = "Call Queue Full",
                summary = "Could not enqueue Messenger call for $targetRecipient.",
                isError = true
            )
        }
    }

    private suspend fun handleFollowUpMessage(
        message: IncomingPageMessage,
        settings: AppSettings,
        decision: IngestionDecision
    ) {
        setStepState(STEP_MONITOR, PipelineStepState.ACTIVE, null)
        if (!settings.geminiEnabled || settings.geminiApiKey.isBlank()) {
            return
        }

        // If a newer burst message already arrived while this one waited for the conversation lock,
        // yield so the newest turn replies ONCE after re-reading the entire burst + opening message.
        if (sessionManager.hasNewerUserMessage(message.conversationKey, decision.messageSequence)) {
            return
        }

        // Always re-read the complete, up-to-date conversation starting from Message #1 before calling Gemini.
        val fullContext = sessionManager.getFullConversationContext(message.conversationKey)
        val contextCount = fullContext.turns.size

        setStepState(
            STEP_GEMINI,
            PipelineStepState.ACTIVE,
            "Re-reading full conversation ($contextCount turns) & generating Gemini reply..."
        )
        val result = geminiClient.generateReply(
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel,
            systemPrompt = settings.geminiSystemPrompt,
            senderName = message.senderName,
            history = fullContext.turns,
            latestMessage = message.messageText,
            openingMessage = fullContext.openingUserMessage
        )

        if (emergencyStopFlag.get()) {
            return
        }

        result.fold(
            onSuccess = { aiReply ->
                val sendResult = message.replyAction(aiReply)
                sendResult.fold(
                    onSuccess = {
                        sessionManager.recordAssistantReply(message.conversationKey, aiReply)
                        setStepState(
                            STEP_GEMINI,
                            PipelineStepState.COMPLETED,
                            "Gemini follow-up sent to ${message.senderName} ($contextCount turns read)"
                        )
                        logEvent(
                            type = LogEventType.GEMINI_REPLY,
                            title = "Gemini Follow-Up Sent",
                            summary = "Read $contextCount convo turns (from 1st msg) • Replied via ${settings.geminiModel} to ${message.senderName}: \"${aiReply.take(140)}\""
                        )
                    },
                    onFailure = { sendErr ->
                        setStepState(
                            STEP_GEMINI,
                            PipelineStepState.FAILED,
                            "Gemini reply send failed: ${sendErr.message}"
                        )
                        logEvent(
                            type = LogEventType.ERROR,
                            title = "Gemini Reply Dispatch Failed",
                            summary = "Generated reply could not be sent inline: ${sendErr.message}",
                            isError = true
                        )
                    }
                )
            },
            onFailure = { err ->
                setStepState(
                    STEP_GEMINI,
                    PipelineStepState.FAILED,
                    "Gemini request failed: ${err.message}"
                )
                logEvent(
                    type = LogEventType.ERROR,
                    title = "Gemini Generation Failed",
                    summary = err.message ?: "Unknown Gemini API error.",
                    isError = true
                )
            }
        )
    }

    private suspend fun processCallRequest(request: CallRequest) {
        val settings = settingsStore.getCurrentSettings()
        if (settings.emergencyStopped || emergencyStopFlag.get()) {
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.EMERGENCY_STOPPED,
                summary = "Aborted before start due to Emergency Stop."
            )
            return
        }

        val targetName = request.savedRecipient.displayName.ifBlank { request.secondaryAccountName }.trim()
        if (targetName.isBlank()) {
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_IDENTITY_MISMATCH,
                summary = "No secondary Messenger account configured."
            )
            return
        }

        val dedupeKey = RecipientIdentityVerifier.normalizeName(targetName)
        val acquireResult = callGuard.tryAcquire(
            requestId = request.requestId,
            dedupeKey = dedupeKey,
            cooldownSeconds = settings.duplicateCallCooldownSeconds,
            enforceCooldown = !request.dryRunOnly
        )

        if (acquireResult is CallGuard.AcquireResult.Rejected) {
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.SKIPPED_DUPLICATE,
                summary = acquireResult.reason
            )
            return
        }

        if (!isMessengerInstalled()) {
            callGuard.release(request.requestId, recordCooldown = false)
            _liveState.update { it.copy(isMessengerInstalled = false) }
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_MESSENGER_NOT_INSTALLED,
                summary = "Official Facebook Messenger ($MESSENGER_PACKAGE) is not installed on this device/emulator."
            )
            return
        }

        val driver = AccessibilityServiceBridge.driver
        if (driver == null || !driver.isConnected()) {
            callGuard.release(request.requestId, recordCooldown = false)
            _liveState.update { it.copy(isAccessibilityServiceConnected = false) }
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_SERVICE_UNAVAILABLE,
                summary = "Enable the PageRelay Accessibility Service in Android Settings before running Messenger call automation."
            )
            return
        }

        var wasEmergencyStopped = false
        val job = scope.launch {
            runExperimentalMessengerCallFlow(request, targetName, driver)
        }
        activeCallJob = job

        try {
            job.join()
        } catch (ce: CancellationException) {
            wasEmergencyStopped = true
            throw ce
        } finally {
            if (activeCallJob === job) {
                activeCallJob = null
            }
            if (emergencyStopFlag.get()) {
                wasEmergencyStopped = true
            }
            callGuard.release(
                requestId = request.requestId,
                recordCooldown = !request.dryRunOnly && !wasEmergencyStopped
            )
            _liveState.update {
                it.copy(
                    isCallInProgress = false,
                    currentCallElapsedSeconds = 0
                )
            }
            notificationHelper.dismiss()
        }
    }

    private suspend fun runExperimentalMessengerCallFlow(
        request: CallRequest,
        targetName: String,
        driver: MessengerAccessibilityDriver
    ) {
        val profile = request.emulatorProfile
        val modeLabel = if (request.dryRunOnly) "Dry-Run" else "Audio Call"

        _liveState.update { state ->
            state.copy(
                isCallInProgress = true,
                currentCallStatus = CallObservableStatus.LAUNCHING_MESSENGER,
                currentCallElapsedSeconds = 0,
                currentStepMessage = "$modeLabel: Launching Facebook Messenger...",
                pipelineSteps = updateSteps(
                    state.pipelineSteps,
                    STEP_OPEN_MESSENGER to PipelineStepState.ACTIVE,
                    STEP_LOCATE_CHAT to PipelineStepState.IDLE,
                    STEP_VERIFY_IDENTITY to PipelineStepState.IDLE,
                    STEP_START_CALL to PipelineStepState.IDLE,
                    STEP_OBSERVE_STATUS to PipelineStepState.IDLE
                )
            )
        }
        notificationHelper.showActiveStatus(
            statusTitle = "PageRelay $modeLabel Active",
            statusText = "Launching Facebook Messenger for $targetName"
        )

        try {
            // Step 3: Launch official Facebook Messenger only
            if (!launchOfficialMessenger()) {
                markCallFailureStep(STEP_OPEN_MESSENGER)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_MESSENGER_NOT_INSTALLED,
                    summary = "Failed to launch $MESSENGER_PACKAGE."
                )
                return
            }
            delay(profile.launchWaitMs)
            checkNotEmergencyStopped()
            setStepState(STEP_OPEN_MESSENGER, PipelineStepState.COMPLETED, "Messenger launched")

            // Step 4: Locate configured recipient conversation
            updateCallStage(
                stepIndex = STEP_LOCATE_CHAT,
                status = CallObservableStatus.NAVIGATING_CONVERSATION,
                message = "Locating Messenger conversation for $targetName..."
            )
            notificationHelper.showActiveStatus(
                statusTitle = "PageRelay $modeLabel Active",
                statusText = "Locating conversation: $targetName"
            )

            var activeTree = pollForMessengerTree(driver, profile.nodePollTimeoutMs)
            if (activeTree == null) {
                markCallFailureStep(STEP_LOCATE_CHAT)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_TIMEOUT,
                    summary = "Timed out waiting for Facebook Messenger window to appear."
                )
                return
            }

            // Check if Messenger is already open on the verified target conversation.
            val alreadyVerified = isAlreadyInVerifiedConversation(activeTree, request)
            if (!alreadyVerified) {
                val navigated = navigateToTargetConversation(
                    driver = driver,
                    initialTree = activeTree,
                    targetName = targetName,
                    secondaryLabel = request.savedRecipient.secondaryLabel.takeIf { it.isNotBlank() },
                    request = request
                )
                if (!navigated) {
                    return
                }
                delay(profile.searchSettleMs)
                checkNotEmergencyStopped()
                activeTree = pollForMessengerTree(driver, profile.nodePollTimeoutMs)
            }

            if (activeTree == null) {
                markCallFailureStep(STEP_LOCATE_CHAT)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_TIMEOUT,
                    summary = "Timed out waiting for recipient chat screen to load."
                )
                return
            }
            setStepState(STEP_LOCATE_CHAT, PipelineStepState.COMPLETED, "Conversation opened")

            // Step 5: Strict recipient identity verification (NEVER call unintended accounts)
            updateCallStage(
                stepIndex = STEP_VERIFY_IDENTITY,
                status = CallObservableStatus.VERIFYING_RECIPIENT,
                message = "Verifying conversation header matches $targetName..."
            )

            val (verifiedTree, verification) = verifyRecipientWithRetries(
                driver = driver,
                initialTree = activeTree,
                request = request,
                timeoutMs = (profile.nodePollTimeoutMs / 2L).coerceAtLeast(2500L)
            )

            when (verification) {
                is VerificationResult.Matched -> {
                    setStepState(
                        STEP_VERIFY_IDENTITY,
                        PipelineStepState.COMPLETED,
                        "Verified recipient: ${verification.matchedHeader}"
                    )
                }

                is VerificationResult.Mismatch -> {
                    markCallFailureStep(STEP_VERIFY_IDENTITY)
                    recordCallOutcome(
                        request = request,
                        status = CallObservableStatus.FAILED_IDENTITY_MISMATCH,
                        summary = "${verification.reason} Observed headers: ${verification.observedHeaders.joinToString(", ")}",
                        diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(verifiedTree?.rootSnapshot)
                    )
                    return
                }

                is VerificationResult.Unavailable -> {
                    markCallFailureStep(STEP_VERIFY_IDENTITY)
                    recordCallOutcome(
                        request = request,
                        status = CallObservableStatus.FAILED_IDENTITY_MISMATCH,
                        summary = verification.reason,
                        diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(verifiedTree?.rootSnapshot)
                    )
                    return
                }
            }

            // Step 6: Locate Audio-ONLY Call control
            updateCallStage(
                stepIndex = STEP_START_CALL,
                status = CallObservableStatus.INITIATING_AUDIO_CALL,
                message = "Locating Messenger Audio Call control..."
            )

            val chatTree = verifiedTree ?: pollForMessengerTree(driver, profile.nodePollTimeoutMs)
            val audioButton = chatTree?.let { MessengerNodeFinder.findAudioCallButton(it.rootSnapshot) }
            if (chatTree == null || audioButton == null) {
                markCallFailureStep(STEP_START_CALL)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                    summary = "Could not locate an Audio-Only Call button in Messenger (video/cellular controls are strictly excluded).",
                    diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(chatTree?.rootSnapshot)
                )
                return
            }

            if (request.dryRunOnly) {
                _liveState.update { state ->
                    state.copy(
                        currentCallStatus = CallObservableStatus.DRY_RUN_VERIFIED,
                        currentStepMessage = "Dry-run passed: verified '$targetName' and audio call control.",
                        pipelineSteps = updateSteps(
                            state.pipelineSteps,
                            STEP_START_CALL to PipelineStepState.COMPLETED,
                            STEP_OBSERVE_STATUS to PipelineStepState.COMPLETED
                        )
                    )
                }
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.DRY_RUN_VERIFIED,
                    summary = "Dry-run verified recipient '${verification.matchedHeader}' (${verification.confidence.name}) and located Audio Call button without placing a call."
                )
                return
            }

            checkNotEmergencyStopped()
            val clicked = driver.clickNode(chatTree, audioButton)
            if (!clicked) {
                markCallFailureStep(STEP_START_CALL)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                    summary = "Located Audio Call button but accessibility click failed.",
                    diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(chatTree.rootSnapshot)
                )
                return
            }

            setStepState(
                STEP_START_CALL,
                PipelineStepState.COMPLETED,
                "Audio call control tapped — observing Messenger UI"
            )

            // Step 7: Observe verifiable call UI state & enforce max call duration
            observeAndManageLiveCall(request, targetName, driver)
        } catch (ce: CancellationException) {
            if (emergencyStopFlag.get()) {
                attemptBestEffortEndCall(driver)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.EMERGENCY_STOPPED,
                    summary = "Call automation halted immediately by Emergency Stop."
                )
            }
            throw ce
        } catch (t: Throwable) {
            markCallFailureStep(STEP_OBSERVE_STATUS)
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.ERROR,
                summary = "Unexpected call automation error: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }

    private suspend fun navigateToTargetConversation(
        driver: MessengerAccessibilityDriver,
        initialTree: LiveAccessibilityTree,
        targetName: String,
        secondaryLabel: String?,
        request: CallRequest
    ): Boolean {
        val profile = request.emulatorProfile
        var tree: LiveAccessibilityTree? = initialTree

        // Try finding Search entry; if inside another chat thread, press Back once and re-check.
        var searchEntry = MessengerNodeFinder.findSearchEntry(tree!!.rootSnapshot)
        if (searchEntry == null && MessengerNodeFinder.findSearchInput(tree.rootSnapshot) == null) {
            driver.performBack()
            delay(profile.stepDelayMs)
            checkNotEmergencyStopped()
            tree = pollForMessengerTree(driver, profile.nodePollTimeoutMs)
            if (tree != null) {
                searchEntry = MessengerNodeFinder.findSearchEntry(tree.rootSnapshot)
            }
        }

        if (tree == null) {
            markCallFailureStep(STEP_LOCATE_CHAT)
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_TIMEOUT,
                summary = "Messenger tree became unavailable while locating Search."
            )
            return false
        }

        var searchInput = MessengerNodeFinder.findSearchInput(tree.rootSnapshot)
        if (searchInput == null) {
            if (searchEntry == null) {
                markCallFailureStep(STEP_LOCATE_CHAT)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                    summary = "Could not locate Messenger Search bar or button.",
                    diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(tree.rootSnapshot)
                )
                return false
            }

            if (!driver.clickNode(tree, searchEntry)) {
                markCallFailureStep(STEP_LOCATE_CHAT)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                    summary = "Failed to click Messenger Search entry.",
                    diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(tree.rootSnapshot)
                )
                return false
            }

            delay(profile.stepDelayMs)
            checkNotEmergencyStopped()

            val polledInput = pollForNode(driver, profile.nodePollTimeoutMs) { snapshot ->
                MessengerNodeFinder.findSearchInput(snapshot)
            }
            if (polledInput == null) {
                val latest = driver.captureActiveTree()
                markCallFailureStep(STEP_LOCATE_CHAT)
                recordCallOutcome(
                    request = request,
                    status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                    summary = "Messenger search input box did not appear after tapping Search.",
                    diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(latest?.rootSnapshot)
                )
                return false
            }
            tree = polledInput.first
            searchInput = polledInput.second
        }

        val entered = driver.setNodeText(tree, searchInput, targetName)
        if (!entered) {
            markCallFailureStep(STEP_LOCATE_CHAT)
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                summary = "Unable to enter recipient name '$targetName' into Messenger search input.",
                diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(tree.rootSnapshot)
            )
            return false
        }

        delay(profile.searchSettleMs)
        checkNotEmergencyStopped()

        val resultMatch = pollForNode(driver, profile.nodePollTimeoutMs) { snapshot ->
            MessengerNodeFinder.findRecipientResultRow(snapshot, targetName, secondaryLabel)
        }
        if (resultMatch == null) {
            val latest = driver.captureActiveTree()
            markCallFailureStep(STEP_LOCATE_CHAT)
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                summary = "Could not find a matching Messenger search result for '$targetName'.",
                diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(latest?.rootSnapshot)
            )
            return false
        }

        val clickedRow = driver.clickNode(resultMatch.first, resultMatch.second)
        if (!clickedRow) {
            markCallFailureStep(STEP_LOCATE_CHAT)
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.FAILED_UI_NOT_FOUND,
                summary = "Found search result for '$targetName' but clicking the row failed.",
                diagnostics = MessengerNodeFinder.summarizeNodeTreeForDiagnostics(resultMatch.first.rootSnapshot)
            )
            return false
        }

        return true
    }

    private suspend fun observeAndManageLiveCall(
        request: CallRequest,
        targetName: String,
        driver: MessengerAccessibilityDriver
    ) {
        var bestObservedStatus = CallObservableStatus.CALL_INITIATED_UNVERIFIED
        var observedTimerText: String? = null

        _liveState.update { state ->
            state.copy(
                currentCallStatus = bestObservedStatus,
                currentCallElapsedSeconds = 0,
                currentStepMessage = "Audio call tapped — connection unverified until call timer appears",
                pipelineSteps = updateSteps(
                    state.pipelineSteps,
                    STEP_OBSERVE_STATUS to PipelineStepState.ACTIVE
                )
            )
        }

        val maxSeconds = request.maxDurationSeconds.coerceAtLeast(10)
        val pollLimitSeconds = if (request.autoEndCall) maxSeconds else HANDOFF_OBSERVE_SECONDS

        var elapsedSeconds = 0
        while (elapsedSeconds < pollLimitSeconds) {
            delay(1000L)
            checkNotEmergencyStopped()
            elapsedSeconds += 1

            val tree = driver.captureActiveTree()
            if (tree != null) {
                val observation = MessengerNodeFinder.observeCallScreenState(tree.rootSnapshot)
                when (observation.state) {
                    ObservedCallScreenState.ACTIVE_CALL_TIMER_OBSERVED -> {
                        bestObservedStatus = CallObservableStatus.ACTIVE_CALL_TIMER_OBSERVED
                        observedTimerText = observation.observedTimerText
                    }

                    ObservedCallScreenState.RINGING_OBSERVED -> {
                        if (bestObservedStatus != CallObservableStatus.ACTIVE_CALL_TIMER_OBSERVED) {
                            bestObservedStatus = CallObservableStatus.RINGING_OBSERVED
                        }
                    }

                    ObservedCallScreenState.UNKNOWN -> {
                        // Never promote to connected or ringing when UI does not verify it.
                    }
                }
            }

            val statusLine = when (bestObservedStatus) {
                CallObservableStatus.ACTIVE_CALL_TIMER_OBSERVED ->
                    "Active call timer observed (${observedTimerText ?: "${elapsedSeconds}s"}) — ${elapsedSeconds}s/${pollLimitSeconds}s"
                CallObservableStatus.RINGING_OBSERVED ->
                    "Ringing / calling UI observed — ${elapsedSeconds}s/${pollLimitSeconds}s"
                else ->
                    "Audio call tapped (connection unverified) — ${elapsedSeconds}s/${pollLimitSeconds}s"
            }

            _liveState.update {
                it.copy(
                    currentCallStatus = bestObservedStatus,
                    currentCallElapsedSeconds = elapsedSeconds,
                    currentStepMessage = statusLine
                )
            }
            notificationHelper.showActiveStatus(
                statusTitle = "PageRelay Call Monitor ($targetName)",
                statusText = statusLine
            )
        }

        if (request.autoEndCall) {
            val endTree = driver.captureActiveTree()
            val endBtn = endTree?.let { MessengerNodeFinder.findEndCallButton(it.rootSnapshot) }
            val endedByButton = if (endTree != null && endBtn != null) {
                driver.clickNode(endTree, endBtn)
            } else {
                false
            }

            val detailNote = buildString {
                append("Reached max duration (${maxSeconds}s). ")
                append("Highest observable state: ${bestObservedStatus.label}")
                if (!observedTimerText.isNullOrBlank()) {
                    append(" (timer $observedTimerText)")
                }
                append(". ")
                append(
                    if (endedByButton) {
                        "Tapped accessible End Call button."
                    } else {
                        "End Call button was not visible (call may have already ended)."
                    }
                )
            }

            setStepState(
                STEP_OBSERVE_STATUS,
                PipelineStepState.COMPLETED,
                "Auto-ended after ${elapsedSeconds}s (${bestObservedStatus.label})"
            )
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.ENDED_AUTOMATICALLY,
                summary = detailNote,
                durationSeconds = elapsedSeconds
            )
        } else {
            val summary = buildString {
                append("Auto-end disabled; handed off audio call after ${elapsedSeconds}s observation. ")
                append("Highest observable state: ${bestObservedStatus.label}")
                if (!observedTimerText.isNullOrBlank()) {
                    append(" (timer $observedTimerText)")
                }
                append(".")
            }
            setStepState(
                STEP_OBSERVE_STATUS,
                PipelineStepState.COMPLETED,
                "Audio call handed off (${bestObservedStatus.label})"
            )
            recordCallOutcome(
                request = request,
                status = CallObservableStatus.COMPLETED_HANDOFF,
                summary = summary,
                durationSeconds = elapsedSeconds
            )
        }
    }

    private fun isAlreadyInVerifiedConversation(
        tree: LiveAccessibilityTree,
        request: CallRequest
    ): Boolean {
        val hasAudioBtn = MessengerNodeFinder.findAudioCallButton(tree.rootSnapshot) != null
        if (!hasAudioBtn) return false
        val header = MessengerNodeFinder.extractConversationHeader(tree.rootSnapshot)
        val result = RecipientIdentityVerifier.verify(
            targetAccountName = request.secondaryAccountName,
            savedRecipient = request.savedRecipient,
            observed = header
        )
        return result is VerificationResult.Matched
    }

    private suspend fun verifyRecipientWithRetries(
        driver: MessengerAccessibilityDriver,
        initialTree: LiveAccessibilityTree,
        request: CallRequest,
        timeoutMs: Long
    ): Pair<LiveAccessibilityTree?, VerificationResult> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var currentTree: LiveAccessibilityTree? = initialTree
        var lastResult: VerificationResult = VerificationResult.Unavailable("No header read yet.")

        while (System.currentTimeMillis() <= deadline) {
            checkNotEmergencyStopped()
            val tree = currentTree ?: driver.captureActiveTree()
            if (tree != null) {
                val header = MessengerNodeFinder.extractConversationHeader(tree.rootSnapshot)
                val result = RecipientIdentityVerifier.verify(
                    targetAccountName = request.secondaryAccountName,
                    savedRecipient = request.savedRecipient,
                    observed = header
                )
                lastResult = result
                if (result is VerificationResult.Matched) {
                    return tree to result
                }
                // Wait briefly only if header was still loading (Unavailable); re-check once on mismatch
                if (result is VerificationResult.Mismatch && System.currentTimeMillis() + 500L >= deadline) {
                    return tree to result
                }
            }
            delay(350L)
            currentTree = driver.captureActiveTree()
        }
        return currentTree to lastResult
    }

    private suspend fun pollForMessengerTree(
        driver: MessengerAccessibilityDriver,
        timeoutMs: Long
    ): LiveAccessibilityTree? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            checkNotEmergencyStopped()
            val tree = driver.captureActiveTree()
            if (tree != null) {
                return tree
            }
            delay(300L)
        }
        return null
    }

    private suspend fun pollForNode(
        driver: MessengerAccessibilityDriver,
        timeoutMs: Long,
        finder: (UiNodeSnapshot) -> UiNodeSnapshot?
    ): Pair<LiveAccessibilityTree, UiNodeSnapshot>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            checkNotEmergencyStopped()
            val tree = driver.captureActiveTree()
            if (tree != null) {
                val found = finder(tree.rootSnapshot)
                if (found != null) {
                    return tree to found
                }
            }
            delay(300L)
        }
        return null
    }

    private fun handleMessengerWindowForCapture(tree: LiveAccessibilityTree) {
        if (!_liveState.value.isCaptureModeActive) return
        if (!captureInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                // Only capture when inside an actual Messenger chat screen that exposes an Audio Call button.
                val hasAudioButton = MessengerNodeFinder.findAudioCallButton(tree.rootSnapshot) != null
                if (!hasAudioButton) return@launch

                val header = MessengerNodeFinder.extractConversationHeader(tree.rootSnapshot)
                val rawTitle = header.primaryTitle?.trim().orEmpty()
                val normalizedTitle = RecipientIdentityVerifier.normalizeName(rawTitle)
                if (normalizedTitle.isBlank()) return@launch

                val rawSubtitle = header.secondarySubtitle
                    ?.trim()
                    ?.takeIf { !RecipientIdentityVerifier.isStatusOnlyText(it) }
                    .orEmpty()

                val signature = RecipientIdentityVerifier.buildConversationSignature(rawTitle, rawSubtitle)
                val captured = SavedRecipientConversation(
                    displayName = rawTitle,
                    secondaryLabel = rawSubtitle,
                    conversationSignature = signature,
                    capturedAtMillis = System.currentTimeMillis()
                )

                settingsStore.update { current ->
                    current.copy(
                        savedRecipient = captured,
                        secondaryAccountName = current.secondaryAccountName.ifBlank { rawTitle }
                    )
                }

                _liveState.update {
                    it.copy(
                        isCaptureModeActive = false,
                        currentStepMessage = "Captured Messenger recipient: $rawTitle"
                    )
                }

                logEvent(
                    type = LogEventType.CAPTURE_CONVERSATION,
                    title = "Recipient Conversation Captured",
                    summary = buildString {
                        append("Saved verified Messenger conversation '$rawTitle'")
                        if (rawSubtitle.isNotBlank()) append(" ($rawSubtitle)")
                        append(" for strict call identity matching.")
                    },
                    recipientName = rawTitle
                )
            } finally {
                captureInFlight.set(false)
            }
        }
    }

    override fun refreshEnvironmentStatus() {
        val listenerEnabled = PageNotificationListenerService.isServiceConnected ||
            isNotificationListenerEnabledInSettings()
        val accessibilityConnected = AccessibilityServiceBridge.isConnected()
        val messengerInstalled = isMessengerInstalled()

        _liveState.update { state ->
            state.copy(
                isNotificationListenerConnected = listenerEnabled,
                isAccessibilityServiceConnected = accessibilityConnected,
                isMessengerInstalled = messengerInstalled
            )
        }
    }

    override fun triggerTestCall(dryRunOnly: Boolean) {
        scope.launch {
            val settings = settingsStore.getCurrentSettings()
            if (settings.emergencyStopped || emergencyStopFlag.get()) {
                logEvent(
                    type = LogEventType.EMERGENCY_STOP,
                    title = "Test Call Blocked",
                    summary = "Reset Emergency Stop before running a test call.",
                    callStatus = CallObservableStatus.EMERGENCY_STOPPED,
                    isError = true
                )
                return@launch
            }

            val recipient = settings.effectiveRecipientName
            if (recipient.isBlank()) {
                _liveState.update {
                    it.copy(currentStepMessage = "Configure or capture a secondary Messenger recipient first.")
                }
                logEvent(
                    type = LogEventType.ERROR,
                    title = "Missing Secondary Recipient",
                    summary = "Enter or capture a secondary Messenger account name before testing.",
                    isError = true
                )
                return@launch
            }

            val request = CallRequest(
                requestId = "test-${UUID.randomUUID().toString().take(8)}",
                triggerSource = if (dryRunOnly) "Manual Dry-Run Test" else "Manual Audio Call Test",
                secondaryAccountName = settings.secondaryAccountName,
                savedRecipient = settings.savedRecipient,
                maxDurationSeconds = settings.maxCallDurationSeconds,
                autoEndCall = settings.autoEndCallEnabled,
                dryRunOnly = dryRunOnly,
                emulatorProfile = settings.emulatorProfile
            )
            callQueue.trySend(request)
        }
    }

    override fun startRecipientCaptureMode() {
        refreshEnvironmentStatus()
        if (!AccessibilityServiceBridge.isConnected()) {
            _liveState.update {
                it.copy(
                    isCaptureModeActive = false,
                    currentStepMessage = "Enable Accessibility Service first to capture a Messenger conversation."
                )
            }
            return
        }
        if (!isMessengerInstalled()) {
            _liveState.update {
                it.copy(
                    isCaptureModeActive = false,
                    currentStepMessage = "Install official Facebook Messenger first to capture a conversation."
                )
            }
            return
        }

        _liveState.update {
            it.copy(
                isCaptureModeActive = true,
                currentStepMessage = "Capture Mode active — open your secondary account chat in Messenger"
            )
        }
        launchOfficialMessenger()
    }

    override fun cancelRecipientCaptureMode() {
        _liveState.update {
            it.copy(
                isCaptureModeActive = false,
                currentStepMessage = "Recipient capture cancelled."
            )
        }
    }

    override fun triggerEmergencyStop(reason: String) {
        emergencyStopFlag.set(true)
        _liveState.update { state ->
            state.copy(
                isCaptureModeActive = false,
                isCallInProgress = false,
                currentCallStatus = CallObservableStatus.EMERGENCY_STOPPED,
                currentCallElapsedSeconds = 0,
                currentStepMessage = "EMERGENCY STOP: $reason",
                pipelineSteps = List(9) { PipelineStepState.IDLE }
            )
        }

        activeCallJob?.cancel(CancellationException(reason))
        while (callQueue.tryReceive().isSuccess) {
            // Drain any queued pending calls immediately.
        }
        callGuard.reset()
        notificationHelper.dismiss()

        scope.launch {
            settingsStore.update { current ->
                current.copy(
                    emergencyStopped = true,
                    automationEnabled = false
                )
            }
            AccessibilityServiceBridge.driver?.let { driver ->
                attemptBestEffortEndCall(driver)
            }
            logEvent(
                type = LogEventType.EMERGENCY_STOP,
                title = "Emergency Stop Activated",
                summary = "$reason. Automation disabled, call queue cleared, and active call terminated if visible.",
                callStatus = CallObservableStatus.EMERGENCY_STOPPED,
                isError = true
            )
        }
    }

    override fun resetEmergencyStopAndRearm(enableAutomation: Boolean) {
        emergencyStopFlag.set(false)
        callGuard.reset()

        scope.launch {
            settingsStore.update { current ->
                current.copy(
                    emergencyStopped = false,
                    automationEnabled = enableAutomation
                )
            }
            _liveState.update { state ->
                state.copy(
                    currentCallStatus = null,
                    currentCallElapsedSeconds = 0,
                    currentStepMessage = if (enableAutomation) {
                        "Emergency Stop cleared — monitoring armed"
                    } else {
                        "Emergency Stop cleared — standing by"
                    },
                    pipelineSteps = List(9) { PipelineStepState.IDLE }
                )
            }
            logEvent(
                type = LogEventType.SYSTEM_INFO,
                title = "Emergency Stop Reset",
                summary = if (enableAutomation) {
                    "Emergency Stop cleared and automation re-enabled."
                } else {
                    "Emergency Stop cleared. Toggle Automation ON when ready."
                }
            )
        }
    }

    override fun clearConversationSessions() {
        sessionManager.clearAll()
        scope.launch {
            logEvent(
                type = LogEventType.SYSTEM_INFO,
                title = "Sessions Cleared",
                summary = "All tracked conversation sessions were reset."
            )
        }
    }

    override fun resetConversationSession(conversationKey: String) {
        sessionManager.resetSession(conversationKey)
        scope.launch {
            logEvent(
                type = LogEventType.SYSTEM_INFO,
                title = "Session Reset",
                summary = "Reset session '$conversationKey'; next message will trigger initial static reply."
            )
        }
    }

    override suspend fun testGeminiConnection(): GeminiTestResult {
        val settings = settingsStore.getCurrentSettings()
        return geminiClient.testConnection(
            apiKey = settings.geminiApiKey,
            model = settings.geminiModel
        )
    }

    override suspend fun exportLogsSummary(): String = withContext(Dispatchers.Default) {
        val entries = auditLogStore.getRecentSnapshot(limit = 150)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        buildString {
            appendLine("=== PageRelay Audit Log Export ===")
            appendLine("Generated: ${formatter.format(Date())}")
            appendLine("Total Entries: ${entries.size}")
            appendLine()
            for (entry in entries) {
                val ts = formatter.format(Date(entry.timestampMillis))
                append("[$ts] [${entry.type.label}] ${entry.title}")
                if (entry.callStatus != null) {
                    append(" | Status: ${entry.callStatus.label}")
                }
                if (entry.durationSeconds != null) {
                    append(" | Duration: ${entry.durationSeconds}s")
                }
                appendLine()
                appendLine("  ${entry.summary}")
                if (!entry.diagnosticDetails.isNullOrBlank()) {
                    appendLine("  Diagnostics: ${entry.diagnosticDetails.replace("\n", " | ")}")
                }
            }
        }
    }

    private fun attemptBestEffortEndCall(driver: MessengerAccessibilityDriver) {
        try {
            val tree = driver.captureActiveTree() ?: return
            val endBtn = MessengerNodeFinder.findEndCallButton(tree.rootSnapshot) ?: return
            driver.clickNode(tree, endBtn)
        } catch (_: Throwable) {
            // Best-effort cleanup during Emergency Stop.
        }
    }

    private fun launchOfficialMessenger(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(MESSENGER_PACKAGE)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isMessengerInstalled(): Boolean {
        return try {
            context.packageManager.getLaunchIntentForPackage(MESSENGER_PACKAGE) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isNotificationListenerEnabledInSettings(): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val expectedComponent = ComponentName(context, PageNotificationListenerService::class.java)
            flat.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry)
                component == expectedComponent || entry.contains(context.packageName)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun matchesTargetPage(message: IncomingPageMessage, targetPageName: String): Boolean {
        val target = targetPageName.trim()
        if (target.isBlank()) return true
        val lowerTarget = target.lowercase(Locale.ROOT)
        val fields = listOfNotNull(
            message.title,
            message.conversationTitle,
            message.subText,
            message.senderName
        )
        return fields.any { it.lowercase(Locale.ROOT).contains(lowerTarget) }
    }

    private fun checkNotEmergencyStopped() {
        if (emergencyStopFlag.get()) {
            throw CancellationException("Emergency Stop activated")
        }
    }

    private fun updateCallStage(
        stepIndex: Int,
        status: CallObservableStatus,
        message: String
    ) {
        _liveState.update { state ->
            state.copy(
                currentCallStatus = status,
                currentStepMessage = message,
                pipelineSteps = updateSteps(state.pipelineSteps, stepIndex to PipelineStepState.ACTIVE)
            )
        }
    }

    private fun markCallFailureStep(stepIndex: Int) {
        _liveState.update { state ->
            state.copy(
                pipelineSteps = updateSteps(state.pipelineSteps, stepIndex to PipelineStepState.FAILED)
            )
        }
    }

    private fun setStepState(
        stepIndex: Int,
        stepState: PipelineStepState,
        message: String?
    ) {
        _liveState.update { state ->
            state.copy(
                currentStepMessage = message ?: state.currentStepMessage,
                pipelineSteps = updateSteps(state.pipelineSteps, stepIndex to stepState)
            )
        }
    }

    private suspend fun recordCallOutcome(
        request: CallRequest,
        status: CallObservableStatus,
        summary: String,
        durationSeconds: Int? = null,
        diagnostics: String? = null
    ) {
        val recipient = request.savedRecipient.displayName.ifBlank { request.secondaryAccountName }.trim()
        _liveState.update {
            it.copy(
                currentCallStatus = status,
                currentStepMessage = "${status.label}: $summary"
            )
        }
        logEvent(
            type = LogEventType.CALL_ATTEMPT,
            title = "${request.triggerSource} — ${status.label}",
            summary = summary,
            recipientName = recipient.ifBlank { null },
            callStatus = status,
            durationSeconds = durationSeconds,
            diagnosticDetails = diagnostics,
            isError = status.isFailure
        )
    }

    private suspend fun logEvent(
        type: LogEventType,
        title: String,
        summary: String,
        recipientName: String? = null,
        callStatus: CallObservableStatus? = null,
        durationSeconds: Int? = null,
        diagnosticDetails: String? = null,
        isError: Boolean = false
    ) {
        try {
            auditLogStore.insert(
                AuditLogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    type = type,
                    title = title,
                    summary = summary,
                    recipientName = recipientName,
                    callStatus = callStatus,
                    durationSeconds = durationSeconds,
                    diagnosticDetails = diagnosticDetails,
                    isError = isError
                )
            )
        } catch (_: Throwable) {
            // Persistence failure must never crash live automation.
        }
    }

    private fun updateSteps(
        current: List<PipelineStepState>,
        vararg updates: Pair<Int, PipelineStepState>
    ): List<PipelineStepState> {
        val mutable = if (current.size == 9) current.toMutableList() else MutableList(9) { PipelineStepState.IDLE }
        for ((index, state) in updates) {
            if (index in mutable.indices) {
                mutable[index] = state
            }
        }
        return mutable
    }

    companion object {
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        private const val HANDOFF_OBSERVE_SECONDS = 6

        private const val STEP_DETECT = 0
        private const val STEP_STATIC_REPLY = 1
        private const val STEP_OPEN_MESSENGER = 2
        private const val STEP_LOCATE_CHAT = 3
        private const val STEP_VERIFY_IDENTITY = 4
        private const val STEP_START_CALL = 5
        private const val STEP_OBSERVE_STATUS = 6
        private const val STEP_MONITOR = 7
        private const val STEP_GEMINI = 8
    }
}
