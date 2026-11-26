package com.pagerelay.bot.automation

import com.pagerelay.bot.domain.SavedRecipientConversation
import java.text.Normalizer
import java.util.Locale

data class ObservedConversationHeader(
    val primaryTitle: String?,
    val secondarySubtitle: String? = null,
    val candidateTexts: List<String> = emptyList()
)

enum class MatchConfidence {
    EXACT_SAVED_CONVERSATION,
    EXACT_NAME_MATCH,
    NORMALIZED_NAME_MATCH
}

sealed class VerificationResult {
    data class Matched(
        val confidence: MatchConfidence,
        val matchedHeader: String,
        val matchedSubtitle: String?
    ) : VerificationResult()

    data class Mismatch(
        val reason: String,
        val observedHeaders: List<String>
    ) : VerificationResult()

    data class Unavailable(
        val reason: String
    ) : VerificationResult()
}

object RecipientIdentityVerifier {

    private val ZERO_WIDTH_REGEX = Regex("""[\u200B-\u200D\uFEFF]""")
    private val MULTISPACE_REGEX = Regex("""\s+""")

    private val STATUS_ONLY_PATTERNS = listOf(
        Regex("""^active\s+now$""", RegexOption.IGNORE_CASE),
        Regex("""^active\s+today$""", RegexOption.IGNORE_CASE),
        Regex("""^active\s+yesterday$""", RegexOption.IGNORE_CASE),
        Regex("""^active\s+\d+\s*[smhdw]\s+ago$""", RegexOption.IGNORE_CASE),
        Regex(
            """^active\s+\d+\s+(?:sec|secs|second|seconds|min|mins|minute|minutes|hr|hrs|hour|hours|day|days)\s+ago$""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""^you['’]re\s+connected\s+on\s+messenger$""", RegexOption.IGNORE_CASE),
        Regex("""^you['’]re\s+friends\s+on\s+facebook$""", RegexOption.IGNORE_CASE),
        Regex("""^end-to-end\s+encrypted$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:facebook|messenger|online|offline|typing\.{0,3}|verified\s+account)$""", RegexOption.IGNORE_CASE)
    )

    private val TRAILING_STATUS_PATTERNS = listOf(
        Regex("""[\s,·•|\-–—]+active\s+now$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+active\s+today$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+active\s+yesterday$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+active\s+\d+\s*[smhdw]\s+ago$""", RegexOption.IGNORE_CASE),
        Regex(
            """[\s,·•|\-–—]+active\s+\d+\s+(?:sec|secs|second|seconds|min|mins|minute|minutes|hr|hrs|hour|hours|day|days)\s+ago$""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""[\s,·•|\-–—]+you['’]re\s+connected\s+on\s+messenger$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+you['’]re\s+friends\s+on\s+facebook$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+end-to-end\s+encrypted$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+verified\s+account$""", RegexOption.IGNORE_CASE),
        Regex("""[\s,·•|\-–—]+online$""", RegexOption.IGNORE_CASE)
    )

    fun normalizeName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(ZERO_WIDTH_REGEX, "")
            .replace('\u00A0', ' ')
            .trim()

        // If multi-line (e.g. "Alex Rivera\nActive now"), keep the non-status first segment.
        if (text.contains('\n')) {
            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !isStatusOnlyText(it) }
            text = lines.firstOrNull().orEmpty()
        }

        // Strip trailing Messenger status suffixes iteratively.
        var previous: String
        do {
            previous = text
            for (pattern in TRAILING_STATUS_PATTERNS) {
                text = pattern.replace(text, "").trim()
            }
        } while (text != previous && text.isNotEmpty())

        if (isStatusOnlyText(text)) {
            return ""
        }

        return text
            .replace(MULTISPACE_REGEX, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    fun normalizeSecondaryLabel(raw: String?): String {
        val base = normalizeName(raw)
        if (base.isEmpty()) return ""
        return base.removePrefix("@").trim()
    }

    fun isStatusOnlyText(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val cleaned = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(ZERO_WIDTH_REGEX, "")
            .replace(MULTISPACE_REGEX, " ")
            .trim()
        return STATUS_ONLY_PATTERNS.any { it.matches(cleaned) }
    }

    fun buildConversationSignature(displayName: String, secondaryLabel: String?): String {
        val normName = normalizeName(displayName)
        val normSecondary = normalizeSecondaryLabel(secondaryLabel)
        return if (normSecondary.isNotEmpty()) {
            "$normName|$normSecondary"
        } else {
            normName
        }
    }

    fun verify(
        targetAccountName: String,
        savedRecipient: SavedRecipientConversation?,
        observed: ObservedConversationHeader
    ): VerificationResult {
        val captured = savedRecipient?.takeIf { it.isCaptured }
        val expectedRawName = (captured?.displayName?.takeIf { it.isNotBlank() } ?: targetAccountName).trim()
        val expectedNormalized = normalizeName(expectedRawName)
        if (expectedNormalized.isEmpty()) {
            return VerificationResult.Unavailable(
                reason = "No secondary Messenger recipient name is configured."
            )
        }

        val observedPrimaryRaw = observed.primaryTitle?.trim().orEmpty()
        val observedPrimaryNormalized = normalizeName(observedPrimaryRaw)

        val observedHeaders = buildList {
            if (observedPrimaryRaw.isNotBlank()) add(observedPrimaryRaw)
            if (!observed.secondarySubtitle.isNullOrBlank()) add(observed.secondarySubtitle.trim())
            observed.candidateTexts.forEach { candidate ->
                val trimmed = candidate.trim()
                if (trimmed.isNotBlank() && !contains(trimmed)) {
                    add(trimmed)
                }
            }
        }

        if (observedPrimaryNormalized.isEmpty()) {
            return VerificationResult.Unavailable(
                reason = "Messenger conversation header could not be read or contained only status text."
            )
        }

        // Strict normalized equality prevents partial/ambiguous matches against wrong accounts.
        if (observedPrimaryNormalized != expectedNormalized) {
            return VerificationResult.Mismatch(
                reason = "Header '$observedPrimaryRaw' does not match configured recipient '$expectedRawName'.",
                observedHeaders = observedHeaders
            )
        }

        // If a saved secondary label (e.g. handle/subtitle) exists and a real non-status subtitle
        // is observed on screen, both must agree so namesake accounts are rejected.
        val expectedSecondary = normalizeSecondaryLabel(captured?.secondaryLabel)
        val observedSecondary = normalizeSecondaryLabel(observed.secondarySubtitle)
        if (expectedSecondary.isNotEmpty() && observedSecondary.isNotEmpty()) {
            if (observedSecondary != expectedSecondary) {
                return VerificationResult.Mismatch(
                    reason = "Secondary label '${observed.secondarySubtitle}' does not match saved recipient label '${captured?.secondaryLabel}'.",
                    observedHeaders = observedHeaders
                )
            }
            return VerificationResult.Matched(
                confidence = MatchConfidence.EXACT_SAVED_CONVERSATION,
                matchedHeader = observedPrimaryRaw,
                matchedSubtitle = observed.secondarySubtitle?.trim()
            )
        }

        val confidence = when {
            captured != null -> MatchConfidence.EXACT_SAVED_CONVERSATION
            observedPrimaryRaw.equals(expectedRawName, ignoreCase = true) -> MatchConfidence.EXACT_NAME_MATCH
            else -> MatchConfidence.NORMALIZED_NAME_MATCH
        }

        return VerificationResult.Matched(
            confidence = confidence,
            matchedHeader = observedPrimaryRaw,
            matchedSubtitle = observed.secondarySubtitle?.trim()?.takeIf { !isStatusOnlyText(it) }
        )
    }
}
