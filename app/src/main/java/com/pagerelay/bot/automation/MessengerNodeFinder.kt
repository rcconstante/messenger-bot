package com.pagerelay.bot.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

data class NodeBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 && height == 0
}

data class UiNodeSnapshot(
    val nodeId: Int,
    val viewIdResourceName: String? = null,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val bounds: NodeBounds = NodeBounds(),
    val children: List<UiNodeSnapshot> = emptyList()
) {
    val primaryLabel: String
        get() = contentDescription?.takeIf { it.isNotBlank() } ?: text.orEmpty()
}

enum class ObservedCallScreenState {
    ACTIVE_CALL_TIMER_OBSERVED,
    RINGING_OBSERVED,
    UNKNOWN
}

data class CallScreenObservation(
    val state: ObservedCallScreenState,
    val observedTimerText: String? = null,
    val matchedIndicators: List<String> = emptyList()
)

class LiveAccessibilityTree(
    val rootSnapshot: UiNodeSnapshot,
    private val liveNodesById: Map<Int, AccessibilityNodeInfo>
) {
    fun resolveLiveNode(nodeId: Int): AccessibilityNodeInfo? = liveNodesById[nodeId]

    fun findClickableLiveNode(nodeId: Int): AccessibilityNodeInfo? {
        var current = liveNodesById[nodeId] ?: return null
        var hops = 0
        while (hops < 6) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            current = current.parent ?: break
            hops++
        }
        return liveNodesById[nodeId]
    }

    companion object {
        private const val MAX_DEPTH = 24
        private const val DEFAULT_MAX_NODES = 500

        fun capture(
            root: AccessibilityNodeInfo?,
            maxNodes: Int = DEFAULT_MAX_NODES
        ): LiveAccessibilityTree? {
            if (root == null) return null
            val liveMap = HashMap<Int, AccessibilityNodeInfo>()
            var nextId = 1

            fun traverse(node: AccessibilityNodeInfo, depth: Int): UiNodeSnapshot {
                val id = nextId++
                liveMap[id] = node

                val rect = Rect()
                try {
                    node.getBoundsInScreen(rect)
                } catch (_: Throwable) {
                    rect.setEmpty()
                }

                val childSnapshots = if (depth < MAX_DEPTH && nextId < maxNodes) {
                    val count = node.childCount.coerceAtLeast(0)
                    val list = ArrayList<UiNodeSnapshot>(count)
                    for (i in 0 until count) {
                        if (nextId >= maxNodes) break
                        val child = try {
                            node.getChild(i)
                        } catch (_: Throwable) {
                            null
                        }
                        if (child != null) {
                            list.add(traverse(child, depth + 1))
                        }
                    }
                    list
                } else {
                    emptyList()
                }

                return UiNodeSnapshot(
                    nodeId = id,
                    viewIdResourceName = node.viewIdResourceName,
                    className = node.className?.toString(),
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isEnabled = node.isEnabled,
                    isVisibleToUser = node.isVisibleToUser,
                    bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
                    children = childSnapshots
                )
            }

            val rootSnapshot = traverse(root, 0)
            return LiveAccessibilityTree(rootSnapshot, liveMap)
        }
    }
}

object MessengerNodeFinder {

    private val CALL_TIMER_REGEX = Regex("""(?<!\d)(?:\d{1,2}:)?\d{1,2}:\d{2}(?!\d)""")
    private val CLOCK_AM_PM_REGEX = Regex("""(?i)\b\d{1,2}:\d{2}\s*(?:am|pm)\b""")

    private val FORBIDDEN_AUDIO_CALL_TOKENS = listOf(
        "video",
        "videocall",
        "video_call",
        "facetime",
        "camera",
        "sms",
        "mms",
        "cellular",
        "dialer",
        "phone_app",
        "sim",
        "whatsapp",
        "telegram",
        "end call",
        "end_call",
        "hang up",
        "hang_up",
        "decline",
        "reject",
        "missed",
        "call log",
        "history",
        "callback",
        "info",
        "details"
    )

    private val POSITIVE_AUDIO_CALL_PHRASES = listOf(
        "audio call",
        "voice call",
        "start a voice call",
        "start voice call",
        "start an audio call",
        "start audio call",
        "call audio",
        "audio_call",
        "voice_call",
        "call_audio",
        "call_voice"
    )

    private val END_CALL_PHRASES = listOf(
        "end call",
        "hang up",
        "leave call",
        "disconnect",
        "end_call",
        "hang_up",
        "hangup",
        "leave_call",
        "end_voice_call",
        "end_audio_call"
    )

    private val ACTIVE_CALL_CONTROL_TOKENS = listOf(
        "mute",
        "unmute",
        "speaker",
        "bluetooth",
        "audio route",
        "keypad",
        "end call",
        "hang up",
        "leave call",
        "end_call",
        "hang_up",
        "mute_button",
        "speaker_button"
    )

    private val RINGING_STATUS_TOKENS = listOf(
        "calling",
        "ringing",
        "connecting",
        "dialing",
        "waiting for"
    )

    private val SEARCH_ENTRY_ID_HINTS = listOf(
        "search_bar",
        "search_box",
        "search_entry",
        "search_icon",
        "search_button",
        "omnibox",
        "search"
    )

    private val SEARCH_ENTRY_LABEL_HINTS = listOf(
        "search messenger",
        "ask meta ai or search",
        "search people",
        "search chats",
        "search"
    )

    private val COMPOSER_EXCLUSION_HINTS = listOf(
        "composer",
        "message_input",
        "text_input",
        "type a message",
        "write a message",
        "message",
        "reply",
        "send"
    )

    private val HEADER_CONTAINER_HINTS = listOf(
        "toolbar",
        "action_bar",
        "title_bar",
        "thread_header",
        "header",
        "top_bar"
    )

    private val HEADER_TITLE_ID_HINTS = listOf(
        "thread_title",
        "thread_name",
        "conversation_title",
        "title_text",
        "header_title",
        "recipient_name",
        "title"
    )

    private val HEADER_SUBTITLE_ID_HINTS = listOf(
        "thread_subtitle",
        "subtitle",
        "presence",
        "username",
        "handle"
    )

    private val IGNORED_HEADER_LABELS = setOf(
        "back",
        "navigate up",
        "close",
        "audio call",
        "voice call",
        "video call",
        "call",
        "info",
        "details",
        "conversation details",
        "thread details",
        "search",
        "more options",
        "chats",
        "messenger",
        "sent",
        "delivered",
        "seen",
        "aa"
    )

    private data class IndexedNode(
        val node: UiNodeSnapshot,
        val parent: IndexedNode?
    ) {
        fun nearestClickable(): UiNodeSnapshot? {
            var current: IndexedNode? = this
            var hops = 0
            while (current != null && hops < 5) {
                if (current.node.isClickable && current.node.isEnabled) {
                    return current.node
                }
                current = current.parent
                hops++
            }
            return null
        }

        fun hasAncestorMatching(predicate: (UiNodeSnapshot) -> Boolean): Boolean {
            var current = parent
            while (current != null) {
                if (predicate(current.node)) return true
                current = current.parent
            }
            return false
        }
    }

    fun findSearchEntry(root: UiNodeSnapshot): UiNodeSnapshot? {
        val indexed = flattenVisible(root)
        var best: Pair<Int, UiNodeSnapshot>? = null

        for (entry in indexed) {
            val node = entry.node
            if (!node.isEnabled) continue
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.trim()?.lowercase(Locale.ROOT).orEmpty()

            if (id.contains("clear") || desc.contains("clear") || desc.contains("close") || desc == "back") {
                continue
            }
            if (isLikelyMessageComposer(id, desc, text)) continue

            var score = 0
            if (SEARCH_ENTRY_ID_HINTS.any { id.contains(it) }) {
                score += 60
            }
            if (SEARCH_ENTRY_LABEL_HINTS.any { desc == it || text == it }) {
                score += 55
            } else if (SEARCH_ENTRY_LABEL_HINTS.any { desc.contains(it) || text.contains(it) }) {
                score += 35
            }

            if (score == 0) continue

            val targetNode = when {
                node.isEditable -> node
                node.isClickable -> node
                else -> entry.nearestClickable() ?: continue
            }

            if (targetNode.isEditable) score += 10
            if (best == null || score > best.first) {
                best = score to targetNode
            }
        }
        return best?.second
    }

    fun findSearchInput(root: UiNodeSnapshot): UiNodeSnapshot? {
        val indexed = flattenVisible(root)
        var best: Pair<Int, UiNodeSnapshot>? = null

        for (entry in indexed) {
            val node = entry.node
            if (!node.isEnabled) continue
            val isEditClass = node.className?.contains("EditText", ignoreCase = true) == true
            if (!node.isEditable && !isEditClass) continue

            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.trim()?.lowercase(Locale.ROOT).orEmpty()

            // Never mistake an in-thread message composer for the Messenger search input.
            if (isLikelyMessageComposer(id, desc, text) && !id.contains("search") && !desc.contains("search")) {
                continue
            }

            var score = 10
            if (id.contains("search") || id.contains("omnibox")) score += 60
            if (desc.contains("search") || text.contains("search")) score += 45
            if (!node.bounds.isEmpty && node.bounds.top in 0..500) score += 20
            if (node.isEditable) score += 10

            if (best == null || score > best.first) {
                best = score to node
            }
        }
        return best?.second
    }

    fun findRecipientResultRow(
        root: UiNodeSnapshot,
        targetName: String,
        secondaryLabel: String? = null
    ): UiNodeSnapshot? {
        val normalizedTarget = RecipientIdentityVerifier.normalizeName(targetName)
        if (normalizedTarget.isEmpty()) return null
        val normalizedSecondary = RecipientIdentityVerifier.normalizeSecondaryLabel(secondaryLabel)

        val indexed = flattenVisible(root)
        var best: Pair<Int, UiNodeSnapshot>? = null

        for (entry in indexed) {
            val node = entry.node
            if (!node.isEnabled) continue
            if (node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true) {
                continue
            }

            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            if (id.contains("search_input") || id.contains("search_box") || id.contains("toolbar")) {
                continue
            }

            val rawCandidates = listOfNotNull(node.text, node.contentDescription)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var matchScore = 0
            for (raw in rawCandidates) {
                val normalized = RecipientIdentityVerifier.normalizeName(raw)
                if (normalized.isEmpty()) continue
                when {
                    normalized == normalizedTarget -> matchScore = maxOf(matchScore, 100)
                    normalized.startsWith("$normalizedTarget ") -> matchScore = maxOf(matchScore, 65)
                }
            }

            if (matchScore == 0) continue

            val clickableRow = if (node.isClickable) node else entry.nearestClickable() ?: continue
            // Never click an editable search field container as a result row.
            if (clickableRow.isEditable || containsEditableDescendant(clickableRow)) continue
            if (containsForbiddenAudioTokens(clickableRow)) continue

            var finalScore = matchScore
            if (normalizedSecondary.isNotEmpty()) {
                val rowTexts = collectDescendantTexts(clickableRow)
                val secondaryMatched = rowTexts.any {
                    RecipientIdentityVerifier.normalizeSecondaryLabel(it) == normalizedSecondary
                }
                if (secondaryMatched) {
                    finalScore += 40
                }
            }

            if (best == null || finalScore > best.first) {
                best = finalScore to clickableRow
            }
        }

        return best?.second
    }

    fun extractConversationHeader(root: UiNodeSnapshot): ObservedConversationHeader {
        val indexed = flattenVisible(root)

        val titleCandidates = mutableListOf<Pair<Int, String>>()
        val subtitleCandidates = mutableListOf<Pair<Int, String>>()

        for (entry in indexed) {
            val node = entry.node
            if (node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true) {
                continue
            }
            val rawText = node.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue

            val lowerText = rawText.lowercase(Locale.ROOT)
            if (lowerText in IGNORED_HEADER_LABELS) continue
            if (CLOCK_AM_PM_REGEX.containsMatchIn(rawText)) continue
            if (CALL_TIMER_REGEX.matches(rawText)) continue
            if (rawText.length > 80) continue

            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val inHeaderContainer = entry.hasAncestorMatching { ancestor ->
                val aid = ancestor.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val aclass = ancestor.className?.lowercase(Locale.ROOT).orEmpty()
                HEADER_CONTAINER_HINTS.any { aid.contains(it) } || aclass.contains("toolbar")
            }
            val isTopArea = !node.bounds.isEmpty && node.bounds.top in 0..380 && node.bounds.bottom <= 460
            val isTextView = node.className?.contains("TextView", ignoreCase = true) == true
            val isButton = node.className?.contains("Button", ignoreCase = true) == true && !inHeaderContainer

            if (isButton) continue

            val isExplicitSubtitleId = HEADER_SUBTITLE_ID_HINTS.any { id.contains(it) }
            val isExplicitTitleId = !isExplicitSubtitleId && HEADER_TITLE_ID_HINTS.any { id.contains(it) }

            if (isExplicitSubtitleId) {
                subtitleCandidates.add(90 to rawText)
                continue
            }

            if (isExplicitTitleId) {
                if (!RecipientIdentityVerifier.isStatusOnlyText(rawText)) {
                    titleCandidates.add(100 to rawText)
                } else {
                    subtitleCandidates.add(60 to rawText)
                }
                continue
            }

            if (inHeaderContainer || isTopArea) {
                val baseScore = (if (inHeaderContainer) 50 else 0) +
                    (if (isTopArea) 30 else 0) +
                    (if (isTextView) 10 else 0)
                if (RecipientIdentityVerifier.isStatusOnlyText(rawText)) {
                    subtitleCandidates.add(baseScore to rawText)
                } else if (rawText.startsWith("@")) {
                    subtitleCandidates.add(baseScore + 20 to rawText)
                } else {
                    titleCandidates.add(baseScore to rawText)
                }
            }
        }

        val sortedTitles = titleCandidates
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()

        val primaryTitle = sortedTitles.firstOrNull()

        val sortedSubtitles = subtitleCandidates
            .sortedByDescending { it.first }
            .map { it.second }
            .filter { it != primaryTitle }
            .distinct()

        // If no explicit subtitle was found, a second distinct header text (like a handle) can be subtitle.
        val secondarySubtitle = sortedSubtitles.firstOrNull { !RecipientIdentityVerifier.isStatusOnlyText(it) }
            ?: sortedSubtitles.firstOrNull()
            ?: sortedTitles.drop(1).firstOrNull()

        val allCandidates = (sortedTitles + sortedSubtitles).distinct()
        return ObservedConversationHeader(
            primaryTitle = primaryTitle,
            secondarySubtitle = secondarySubtitle,
            candidateTexts = allCandidates
        )
    }

    /**
     * Strictly locates Messenger's Audio-Only call button.
     * Any node mentioning video, camera, SMS, cellular, dialer, WhatsApp, Telegram, or end-call is strictly rejected.
     */
    fun findAudioCallButton(root: UiNodeSnapshot): UiNodeSnapshot? {
        val indexed = flattenVisible(root)
        var best: Pair<Int, UiNodeSnapshot>? = null

        for (entry in indexed) {
            val node = entry.node
            if (!node.isEnabled) continue
            if (node.isEditable) continue

            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.trim()?.lowercase(Locale.ROOT).orEmpty()

            if (hasForbiddenAudioCallToken(id, desc, text)) continue

            var score = 0
            if (POSITIVE_AUDIO_CALL_PHRASES.any { desc.contains(it) || text.contains(it) }) {
                score += 100
            }
            if (POSITIVE_AUDIO_CALL_PHRASES.any { id.contains(it) }) {
                score += 90
            }
            if (score == 0 && (desc == "call" || text == "call")) {
                score += 60
            }

            if (score == 0) continue

            val clickable = if (node.isClickable) node else entry.nearestClickable() ?: continue
            // Re-verify clickable container does not carry forbidden video/SMS labels.
            if (containsForbiddenAudioTokens(clickable)) continue

            if (!clickable.bounds.isEmpty && clickable.bounds.top in 0..420) {
                score += 15
            }

            if (best == null || score > best.first) {
                best = score to clickable
            }
        }

        return best?.second
    }

    fun findEndCallButton(root: UiNodeSnapshot): UiNodeSnapshot? {
        val indexed = flattenVisible(root)
        var best: Pair<Int, UiNodeSnapshot>? = null

        for (entry in indexed) {
            val node = entry.node
            if (!node.isEnabled) continue
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.trim()?.lowercase(Locale.ROOT).orEmpty()

            var score = 0
            if (END_CALL_PHRASES.any { desc.contains(it) || text.contains(it) }) {
                score += 100
            }
            if (END_CALL_PHRASES.any { id.contains(it) }) {
                score += 90
            }

            if (score == 0) continue

            val clickable = if (node.isClickable) node else entry.nearestClickable() ?: continue
            if (best == null || score > best.first) {
                best = score to clickable
            }
        }

        return best?.second
    }

    /**
     * Classifies the observable Messenger call UI strictly:
     * - ACTIVE_CALL_TIMER_OBSERVED ONLY when BOTH a real MM:SS / HH:MM:SS timer AND an active call control
     *   (End call / Mute / Speaker) are simultaneously visible.
     * - RINGING_OBSERVED when calling/ringing status or End Call button is visible without an active timer.
     * - UNKNOWN otherwise (never claims connected).
     */
    fun observeCallScreenState(root: UiNodeSnapshot): CallScreenObservation {
        val indexed = flattenVisible(root)
        val indicators = mutableListOf<String>()

        val hasEndCall = findEndCallButton(root) != null
        if (hasEndCall) {
            indicators.add("end_call_control")
        }

        var hasActiveCallControl = hasEndCall
        var hasRingingStatus = false
        var observedTimer: String? = null

        for (entry in indexed) {
            val node = entry.node
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.trim().orEmpty()
            val text = node.text?.trim().orEmpty()
            val combinedLower = "$id ${desc.lowercase(Locale.ROOT)} ${text.lowercase(Locale.ROOT)}"

            if (ACTIVE_CALL_CONTROL_TOKENS.any { combinedLower.contains(it) }) {
                hasActiveCallControl = true
                if (indicators.size < 8) {
                    val tag = desc.ifBlank { text }.ifBlank { id }
                    if (tag.isNotBlank() && tag !in indicators) {
                        indicators.add(tag.take(32))
                    }
                }
            }

            if (RINGING_STATUS_TOKENS.any { combinedLower.contains(it) }) {
                hasRingingStatus = true
                if (indicators.size < 8) {
                    val tag = text.ifBlank { desc }
                    if (tag.isNotBlank() && tag !in indicators) {
                        indicators.add(tag.take(32))
                    }
                }
            }

            if (observedTimer == null) {
                val candidate = extractCallDurationTimer(text) ?: extractCallDurationTimer(desc)
                if (candidate != null) {
                    observedTimer = candidate
                }
            }
        }

        return when {
            observedTimer != null && hasActiveCallControl -> CallScreenObservation(
                state = ObservedCallScreenState.ACTIVE_CALL_TIMER_OBSERVED,
                observedTimerText = observedTimer,
                matchedIndicators = indicators + "timer:$observedTimer"
            )
            hasRingingStatus || hasEndCall -> CallScreenObservation(
                state = ObservedCallScreenState.RINGING_OBSERVED,
                observedTimerText = null,
                matchedIndicators = indicators
            )
            else -> CallScreenObservation(
                state = ObservedCallScreenState.UNKNOWN,
                observedTimerText = null,
                matchedIndicators = emptyList()
            )
        }
    }

    /**
     * Produces a safe, bounded diagnostic summary of clickable/header/ID nodes without leaking message bodies.
     */
    fun summarizeNodeTreeForDiagnostics(
        root: UiNodeSnapshot?,
        maxItems: Int = 18
    ): String {
        if (root == null) return "No active accessibility node tree available."
        val indexed = flattenVisible(root)
        val lines = mutableListOf<String>()

        for (entry in indexed) {
            if (lines.size >= maxItems) break
            val node = entry.node
            val shortId = node.viewIdResourceName?.substringAfterLast('/')
            val desc = node.contentDescription?.trim()?.take(36)
            val text = node.text?.trim()?.take(36)
            val interesting = node.isClickable || node.isEditable || shortId != null || !desc.isNullOrBlank()
            if (!interesting) continue

            // Skip long multi-word non-clickable message body paragraphs for privacy.
            val safeText = if (!node.isClickable && !node.isEditable && (node.text?.length ?: 0) > 40) {
                "[redacted_text]"
            } else {
                text
            }

            val shortClass = node.className?.substringAfterLast('.') ?: "Node"
            val parts = buildList {
                add(shortClass)
                if (shortId != null) add("id=$shortId")
                if (!desc.isNullOrBlank()) add("desc=\"$desc\"")
                if (!safeText.isNullOrBlank()) add("text=\"$safeText\"")
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
            }
            lines.add(parts.joinToString(" "))
        }

        return if (lines.isEmpty()) {
            "Accessibility tree captured (${indexed.size} visible nodes) with no identifiable controls."
        } else {
            "Visible controls (${lines.size}/${indexed.size} nodes):\n" + lines.joinToString("\n")
        }
    }

    private fun extractCallDurationTimer(raw: String): String? {
        if (raw.isBlank() || raw.length > 32) return null
        if (CLOCK_AM_PM_REGEX.containsMatchIn(raw)) return null
        val match = CALL_TIMER_REGEX.find(raw) ?: return null
        return match.value
    }

    private fun hasForbiddenAudioCallToken(id: String, desc: String, text: String): Boolean {
        return FORBIDDEN_AUDIO_CALL_TOKENS.any { token ->
            id.contains(token) || desc.contains(token) || text.contains(token)
        }
    }

    private fun containsForbiddenAudioTokens(node: UiNodeSnapshot): Boolean {
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val text = node.text?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return hasForbiddenAudioCallToken(id, desc, text)
    }

    private fun isLikelyMessageComposer(id: String, desc: String, text: String): Boolean {
        return COMPOSER_EXCLUSION_HINTS.any { hint ->
            id.contains(hint) || desc.contains(hint) || text == "aa" || text.contains("type a message")
        }
    }

    private fun containsEditableDescendant(node: UiNodeSnapshot): Boolean {
        if (node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true) {
            return true
        }
        return node.children.any { containsEditableDescendant(it) }
    }

    private fun collectDescendantTexts(node: UiNodeSnapshot): List<String> {
        val result = mutableListOf<String>()
        fun walk(current: UiNodeSnapshot) {
            current.text?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
            current.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
            current.children.forEach { walk(it) }
        }
        walk(node)
        return result
    }

    private fun flattenVisible(root: UiNodeSnapshot): List<IndexedNode> {
        val result = mutableListOf<IndexedNode>()
        fun walk(node: UiNodeSnapshot, parent: IndexedNode?) {
            if (!node.isVisibleToUser) return
            val indexed = IndexedNode(node, parent)
            result.add(indexed)
            for (child in node.children) {
                walk(child, indexed)
            }
        }
        walk(root, null)
        return result
    }
}
