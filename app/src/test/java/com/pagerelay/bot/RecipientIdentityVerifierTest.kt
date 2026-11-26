package com.pagerelay.bot

import com.pagerelay.bot.automation.MatchConfidence
import com.pagerelay.bot.automation.ObservedConversationHeader
import com.pagerelay.bot.automation.RecipientIdentityVerifier
import com.pagerelay.bot.automation.VerificationResult
import com.pagerelay.bot.domain.SavedRecipientConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientIdentityVerifierTest {

    @Test
    fun `matches exact configured secondary account name`() {
        val result = RecipientIdentityVerifier.verify(
            targetAccountName = "Alex Rivera",
            savedRecipient = null,
            observed = ObservedConversationHeader(
                primaryTitle = "Alex Rivera",
                secondarySubtitle = "Active now"
            )
        )

        assertTrue(result is VerificationResult.Matched)
        val matched = result as VerificationResult.Matched
        assertEquals(MatchConfidence.EXACT_NAME_MATCH, matched.confidence)
        assertEquals("Alex Rivera", matched.matchedHeader)
    }

    @Test
    fun `normalizes unicode case whitespace and messenger status suffixes`() {
        val result = RecipientIdentityVerifier.verify(
            targetAccountName = "  alex   rivera ",
            savedRecipient = null,
            observed = ObservedConversationHeader(
                primaryTitle = "ＡＬＥＸ   Rivera · Active 5m ago",
                secondarySubtitle = "You're connected on Messenger"
            )
        )

        assertTrue(result is VerificationResult.Matched)
        val matched = result as VerificationResult.Matched
        assertEquals(MatchConfidence.NORMALIZED_NAME_MATCH, matched.confidence)
    }

    @Test
    fun `strips active now multiline suffix during normalization`() {
        val normalized = RecipientIdentityVerifier.normalizeName("Backup Ops\nActive now")
        assertEquals("backup ops", normalized)

        val result = RecipientIdentityVerifier.verify(
            targetAccountName = "Backup Ops",
            savedRecipient = null,
            observed = ObservedConversationHeader(primaryTitle = "Backup Ops\nActive now")
        )
        assertTrue(result is VerificationResult.Matched)
    }

    @Test
    fun `matches saved recipient conversation with secondary label`() {
        val saved = SavedRecipientConversation(
            displayName = "Jordan Lee",
            secondaryLabel = "@jordan.ops",
            conversationSignature = RecipientIdentityVerifier.buildConversationSignature("Jordan Lee", "@jordan.ops"),
            capturedAtMillis = 1_700_000_000_000L
        )

        val result = RecipientIdentityVerifier.verify(
            targetAccountName = "Fallback Name",
            savedRecipient = saved,
            observed = ObservedConversationHeader(
                primaryTitle = "Jordan Lee",
                secondarySubtitle = "jordan.ops"
            )
        )

        assertTrue(result is VerificationResult.Matched)
        val matched = result as VerificationResult.Matched
        assertEquals(MatchConfidence.EXACT_SAVED_CONVERSATION, matched.confidence)
    }

    @Test
    fun `rejects namesake account when saved secondary label mismatches`() {
        val saved = SavedRecipientConversation(
            displayName = "Jordan Lee",
            secondaryLabel = "@jordan.ops",
            conversationSignature = "jordan lee|jordan.ops",
            capturedAtMillis = 1_700_000_000_000L
        )

        val result = RecipientIdentityVerifier.verify(
            targetAccountName = "Jordan Lee",
            savedRecipient = saved,
            observed = ObservedConversationHeader(
                primaryTitle = "Jordan Lee",
                secondarySubtitle = "@jordan.personal"
            )
        )

        assertTrue(result is VerificationResult.Mismatch)
    }

    @Test
    fun `strictly rejects mismatched or partial account names to prevent wrong calls`() {
        val mismatchDifferent = RecipientIdentityVerifier.verify(
            targetAccountName = "Alex Rivera",
            savedRecipient = null,
            observed = ObservedConversationHeader(primaryTitle = "Sam Taylor")
        )
        assertTrue(mismatchDifferent is VerificationResult.Mismatch)

        val mismatchPartial = RecipientIdentityVerifier.verify(
            targetAccountName = "Alex Rivera",
            savedRecipient = null,
            observed = ObservedConversationHeader(primaryTitle = "Alex")
        )
        assertTrue(mismatchPartial is VerificationResult.Mismatch)
    }

    @Test
    fun `rejects blank or status-only headers as unavailable`() {
        val blankHeader = RecipientIdentityVerifier.verify(
            targetAccountName = "Alex Rivera",
            savedRecipient = null,
            observed = ObservedConversationHeader(primaryTitle = "   ")
        )
        assertTrue(blankHeader is VerificationResult.Unavailable)

        val statusOnlyHeader = RecipientIdentityVerifier.verify(
            targetAccountName = "Alex Rivera",
            savedRecipient = null,
            observed = ObservedConversationHeader(primaryTitle = "Active now")
        )
        assertTrue(statusOnlyHeader is VerificationResult.Unavailable)
    }
}
