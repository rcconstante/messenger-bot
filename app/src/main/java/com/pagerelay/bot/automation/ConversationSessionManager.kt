package com.pagerelay.bot.automation

import com.pagerelay.bot.ai.ConversationTurn
import com.pagerelay.bot.domain.ConversationSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class MessageAction {
    IGNORE_DUPLICATE,
    FIRST_MESSAGE_STATIC_AND_CALL,
    FOLLOWUP_GEMINI
}

data class IngestionDecision(
    val action: MessageAction,
    val conversationKey: String,
    val sessionInfo: ConversationSessionInfo,
    val priorTurns: List<ConversationTurn>,
    val messageSequence: Long = 0L
)

data class ConversationContextSnapshot(
    val conversationKey: String,
    val openingUserMessage: String?,
    val turns: List<ConversationTurn>,
    val totalUserMessages: Int
)

class ConversationSessionManager(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MS
) {

    private data class MutableSession(
        val conversationKey: String,
        var senderTitle: String,
        var messageCount: Int = 0,
        var messageSequence: Long = 0L,
        var initialStaticReplySent: Boolean = false,
        var autoCallTriggered: Boolean = false,
        var openingUserMessage: String? = null,
        var openingAssistantReply: String? = null,
        var lastMessagePreview: String = "",
        var lastUpdatedMillis: Long = 0L,
        val turns: MutableList<ConversationTurn> = mutableListOf(),
        val recentMessageFingerprints: LinkedHashMap<String, Long> = LinkedHashMap()
    ) {
        fun toInfo(): ConversationSessionInfo = ConversationSessionInfo(
            conversationKey = conversationKey,
            senderTitle = senderTitle,
            messageCount = messageCount,
            initialStaticReplySent = initialStaticReplySent,
            autoCallTriggered = autoCallTriggered,
            lastMessagePreview = lastMessagePreview,
            lastUpdatedMillis = lastUpdatedMillis,
            openingMessagePreview = openingUserMessage.orEmpty().take(120),
            contextTurnCount = turns.size
        )
    }

    private val lock = Any()
    private val sessionMap = LinkedHashMap<String, MutableSession>()
    private val _sessions = MutableStateFlow<List<ConversationSessionInfo>>(emptyList())
    val sessions: StateFlow<List<ConversationSessionInfo>> = _sessions.asStateFlow()

    fun onIncomingMessage(
        conversationKey: String,
        senderTitle: String,
        messageText: String,
        notificationHistory: List<ConversationTurn> = emptyList()
    ): IngestionDecision = synchronized(lock) {
        val now = clock()
        val normalizedKey = conversationKey.trim()
        val cleanSender = senderTitle.trim().ifBlank { "Facebook User" }
        val cleanText = messageText.trim()

        val existing = sessionMap[normalizedKey]
        val fingerprint = buildMessageFingerprint(cleanSender, cleanText)

        if (existing != null) {
            pruneFingerprintsLocked(existing, now)
            val lastSeen = existing.recentMessageFingerprints[fingerprint]
            if (lastSeen != null && (now - lastSeen) in 0..dedupeWindowMillis) {
                return IngestionDecision(
                    action = MessageAction.IGNORE_DUPLICATE,
                    conversationKey = normalizedKey,
                    sessionInfo = existing.toInfo(),
                    priorTurns = buildAnchoredTurnsLocked(existing),
                    messageSequence = existing.messageSequence
                )
            }
        }

        val session = existing ?: MutableSession(
            conversationKey = normalizedKey,
            senderTitle = cleanSender,
            lastUpdatedMillis = now
        ).also { sessionMap[normalizedKey] = it }

        // Backfill any earlier/batched messages exposed by Android's MessagingStyle notification
        // BEFORE capturing priorTurns and appending the newest incoming message.
        mergeNotificationHistoryLocked(session, notificationHistory, cleanText)

        val priorTurnsSnapshot = buildAnchoredTurnsLocked(session)
        val isFirstMessage = session.messageCount == 0 && !session.initialStaticReplySent

        session.senderTitle = cleanSender
        session.messageCount += 1
        session.messageSequence += 1L
        if (session.openingUserMessage.isNullOrBlank()) {
            session.openingUserMessage = cleanText
        }
        session.lastMessagePreview = cleanText.take(120)
        session.lastUpdatedMillis = now
        session.recentMessageFingerprints[fingerprint] = now
        appendTurnLocked(session, ConversationTurn(isFromUser = true, text = cleanText))

        val action = if (isFirstMessage) {
            session.initialStaticReplySent = true
            MessageAction.FIRST_MESSAGE_STATIC_AND_CALL
        } else {
            MessageAction.FOLLOWUP_GEMINI
        }

        publishSessionsLocked()
        IngestionDecision(
            action = action,
            conversationKey = normalizedKey,
            sessionInfo = session.toInfo(),
            priorTurns = priorTurnsSnapshot,
            messageSequence = session.messageSequence
        )
    }

    fun recordAssistantReply(conversationKey: String, replyText: String) = synchronized(lock) {
        val session = sessionMap[conversationKey.trim()] ?: return
        val cleanReply = replyText.trim()
        if (cleanReply.isEmpty()) return
        if (session.openingAssistantReply.isNullOrBlank()) {
            session.openingAssistantReply = cleanReply
        }
        appendTurnLocked(session, ConversationTurn(isFromUser = false, text = cleanReply))
        session.lastUpdatedMillis = clock()
        publishSessionsLocked()
    }

    fun hasNewerUserMessage(conversationKey: String, messageSequence: Long): Boolean = synchronized(lock) {
        val session = sessionMap[conversationKey.trim()] ?: return false
        session.messageSequence > messageSequence
    }

    fun getFullConversationContext(conversationKey: String): ConversationContextSnapshot = synchronized(lock) {
        val key = conversationKey.trim()
        val session = sessionMap[key]
        if (session == null) {
            return ConversationContextSnapshot(
                conversationKey = key,
                openingUserMessage = null,
                turns = emptyList(),
                totalUserMessages = 0
            )
        }
        ConversationContextSnapshot(
            conversationKey = key,
            openingUserMessage = session.openingUserMessage,
            turns = buildAnchoredTurnsLocked(session),
            totalUserMessages = session.messageCount
        )
    }

    fun markAutoCallTriggered(conversationKey: String) = synchronized(lock) {
        val session = sessionMap[conversationKey.trim()] ?: return
        session.autoCallTriggered = true
        session.lastUpdatedMillis = clock()
        publishSessionsLocked()
    }

    fun resetSession(conversationKey: String) = synchronized(lock) {
        if (sessionMap.remove(conversationKey.trim()) != null) {
            publishSessionsLocked()
        }
    }

    fun clearAll() = synchronized(lock) {
        sessionMap.clear()
        publishSessionsLocked()
    }

    fun getTurnsSnapshot(conversationKey: String): List<ConversationTurn> = synchronized(lock) {
        val session = sessionMap[conversationKey.trim()] ?: return emptyList()
        buildAnchoredTurnsLocked(session)
    }

    private fun mergeNotificationHistoryLocked(
        session: MutableSession,
        notificationHistory: List<ConversationTurn>,
        currentIncomingText: String
    ) {
        val cleaned = notificationHistory
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
        if (cleaned.isEmpty()) return

        // Drop the trailing copy of the current incoming message (which will be appended by caller).
        val priorFromNotification = if (
            cleaned.last().isFromUser &&
            normalizeForCompare(cleaned.last().text) == normalizeForCompare(currentIncomingText)
        ) {
            cleaned.dropLast(1)
        } else {
            cleaned
        }
        if (priorFromNotification.isEmpty()) return

        if (session.turns.isEmpty()) {
            for (turn in priorFromNotification) {
                if (turn.isFromUser && session.openingUserMessage.isNullOrBlank()) {
                    session.openingUserMessage = turn.text
                } else if (!turn.isFromUser && session.openingAssistantReply.isNullOrBlank()) {
                    session.openingAssistantReply = turn.text
                }
                appendTurnLocked(session, turn)
            }
            return
        }

        // Align the tail of session.turns against priorFromNotification to recover any skipped/batched turns.
        val lastRecorded = session.turns.last()
        var matchEndIndex = -1
        for (i in priorFromNotification.lastIndex downTo 0) {
            if (turnsEqual(priorFromNotification[i], lastRecorded)) {
                var valid = true
                var sIdx = session.turns.lastIndex - 1
                var nIdx = i - 1
                var checked = 0
                while (sIdx >= 0 && nIdx >= 0 && checked < 4) {
                    if (!turnsEqual(priorFromNotification[nIdx], session.turns[sIdx])) {
                        valid = false
                        break
                    }
                    sIdx--
                    nIdx--
                    checked++
                }
                if (valid) {
                    matchEndIndex = i
                    break
                }
            }
        }

        if (matchEndIndex >= 0) {
            // Check if notificationHistory contains earlier messages before session.turns[0]
            val earliestSessionTurn = session.turns.first()
            val firstMatchIdx = priorFromNotification
                .subList(0, matchEndIndex + 1)
                .indexOfFirst { turnsEqual(it, earliestSessionTurn) }
            if (firstMatchIdx > 0) {
                val missingHead = priorFromNotification.subList(0, firstMatchIdx)
                session.turns.addAll(0, missingHead)
                val earliestUser = missingHead.firstOrNull { it.isFromUser }?.text
                if (!earliestUser.isNullOrBlank()) {
                    session.openingUserMessage = earliestUser
                }
                trimRollingTurnsLocked(session)
            }

            // Append any missed/batched turns that occurred after our last recorded turn
            if (matchEndIndex + 1 < priorFromNotification.size) {
                for (j in (matchEndIndex + 1) until priorFromNotification.size) {
                    val missed = priorFromNotification[j]
                    if (missed.isFromUser && session.openingUserMessage.isNullOrBlank()) {
                        session.openingUserMessage = missed.text
                    }
                    appendTurnLocked(session, missed)
                }
            }
        } else if (session.openingUserMessage.isNullOrBlank()) {
            priorFromNotification.firstOrNull { it.isFromUser }?.let { firstUser ->
                session.openingUserMessage = firstUser.text
            }
        }
    }

    private fun buildAnchoredTurnsLocked(session: MutableSession): List<ConversationTurn> {
        val current = session.turns.toList()
        val opening = session.openingUserMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return current
        val hasOpeningAtStart = current.firstOrNull()?.let { first ->
            first.isFromUser && normalizeForCompare(first.text) == normalizeForCompare(opening)
        } == true
        if (hasOpeningAtStart) {
            return current
        }

        val anchored = mutableListOf(ConversationTurn(isFromUser = true, text = opening))
        val openingReply = session.openingAssistantReply?.trim()?.takeIf { it.isNotEmpty() }
        if (openingReply != null) {
            val alreadyHasOpeningReply = current.firstOrNull()?.let { first ->
                !first.isFromUser && normalizeForCompare(first.text) == normalizeForCompare(openingReply)
            } == true
            if (!alreadyHasOpeningReply) {
                anchored.add(ConversationTurn(isFromUser = false, text = openingReply))
            }
        }
        anchored.addAll(current)
        return anchored
    }

    private fun appendTurnLocked(session: MutableSession, turn: ConversationTurn) {
        session.turns.add(turn)
        trimRollingTurnsLocked(session)
    }

    private fun trimRollingTurnsLocked(session: MutableSession) {
        // Keep the opening message (and initial reply if present) anchored; trim from the middle if ever over cap.
        val anchorCount = when {
            session.turns.size >= 2 &&
                session.turns[0].isFromUser &&
                !session.turns[1].isFromUser &&
                normalizeForCompare(session.turns[0].text) == normalizeForCompare(session.openingUserMessage.orEmpty()) -> 2
            session.turns.isNotEmpty() &&
                session.turns[0].isFromUser &&
                normalizeForCompare(session.turns[0].text) == normalizeForCompare(session.openingUserMessage.orEmpty()) -> 1
            else -> 0
        }
        while (session.turns.size > MAX_ROLLING_TURNS) {
            val removeIdx = if (anchorCount < session.turns.size) anchorCount else 0
            session.turns.removeAt(removeIdx)
        }
    }

    private fun turnsEqual(a: ConversationTurn, b: ConversationTurn): Boolean =
        a.isFromUser == b.isFromUser && normalizeForCompare(a.text) == normalizeForCompare(b.text)

    private fun normalizeForCompare(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()

    private fun pruneFingerprintsLocked(session: MutableSession, now: Long) {
        val cutoff = now - dedupeWindowMillis
        session.recentMessageFingerprints.entries.removeAll { it.value < cutoff }
    }

    private fun buildMessageFingerprint(sender: String, text: String): String {
        val normSender = normalizeForCompare(sender)
        val normText = normalizeForCompare(text)
        return "$normSender|$normText"
    }

    private fun publishSessionsLocked() {
        _sessions.value = sessionMap.values
            .map { it.toInfo() }
            .sortedByDescending { it.lastUpdatedMillis }
    }

    companion object {
        const val MAX_ROLLING_TURNS = 40
        const val DEFAULT_DEDUPE_WINDOW_MS = 15_000L
    }
}
