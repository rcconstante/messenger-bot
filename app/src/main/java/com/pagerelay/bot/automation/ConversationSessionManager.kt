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
    val priorTurns: List<ConversationTurn>
)

class ConversationSessionManager(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MS
) {

    private data class MutableSession(
        val conversationKey: String,
        var senderTitle: String,
        var messageCount: Int = 0,
        var initialStaticReplySent: Boolean = false,
        var autoCallTriggered: Boolean = false,
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
            lastUpdatedMillis = lastUpdatedMillis
        )
    }

    private val lock = Any()
    private val sessionMap = LinkedHashMap<String, MutableSession>()
    private val _sessions = MutableStateFlow<List<ConversationSessionInfo>>(emptyList())
    val sessions: StateFlow<List<ConversationSessionInfo>> = _sessions.asStateFlow()

    fun onIncomingMessage(
        conversationKey: String,
        senderTitle: String,
        messageText: String
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
                    priorTurns = existing.turns.toList()
                )
            }
        }

        val session = existing ?: MutableSession(
            conversationKey = normalizedKey,
            senderTitle = cleanSender,
            lastUpdatedMillis = now
        ).also { sessionMap[normalizedKey] = it }

        val priorTurnsSnapshot = session.turns.toList()
        val isFirstMessage = session.messageCount == 0 && !session.initialStaticReplySent

        session.senderTitle = cleanSender
        session.messageCount += 1
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
            priorTurns = priorTurnsSnapshot
        )
    }

    fun recordAssistantReply(conversationKey: String, replyText: String) = synchronized(lock) {
        val session = sessionMap[conversationKey.trim()] ?: return
        val cleanReply = replyText.trim()
        if (cleanReply.isEmpty()) return
        appendTurnLocked(session, ConversationTurn(isFromUser = false, text = cleanReply))
        session.lastUpdatedMillis = clock()
        publishSessionsLocked()
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
        sessionMap[conversationKey.trim()]?.turns?.toList().orEmpty()
    }

    private fun appendTurnLocked(session: MutableSession, turn: ConversationTurn) {
        session.turns.add(turn)
        while (session.turns.size > MAX_ROLLING_TURNS) {
            session.turns.removeAt(0)
        }
    }

    private fun pruneFingerprintsLocked(session: MutableSession, now: Long) {
        val cutoff = now - dedupeWindowMillis
        session.recentMessageFingerprints.entries.removeAll { it.value < cutoff }
    }

    private fun buildMessageFingerprint(sender: String, text: String): String {
        val normSender = sender.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        val normText = text.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
        return "$normSender|$normText"
    }

    private fun publishSessionsLocked() {
        _sessions.value = sessionMap.values
            .map { it.toInfo() }
            .sortedByDescending { it.lastUpdatedMillis }
    }

    companion object {
        const val MAX_ROLLING_TURNS = 8
        const val DEFAULT_DEDUPE_WINDOW_MS = 15_000L
    }
}
