package com.pagerelay.bot

import com.pagerelay.bot.ai.ConversationTurn
import com.pagerelay.bot.ai.GeminiReplyClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiReplyClientTest {

    @Test
    fun `merges consecutive burst messages into valid alternating roles while keeping first message`() {
        val opening = "Grand Prize as of today: iPhone 17 Pro Max 512GB + 200,000 Cash Instant received via PESOnet."
        val history = listOf(
            ConversationTurn(isFromUser = true, text = opening),
            ConversationTurn(isFromUser = false, text = "Present po! Ready!"),
            ConversationTurn(isFromUser = true, text = "1"),
            ConversationTurn(isFromUser = true, text = "2"),
            ConversationTurn(isFromUser = true, text = "3")
        )

        val prepared = GeminiReplyClient.prepareConversationTurns(
            history = history,
            latestMessage = "3",
            openingMessage = opening
        )

        assertEquals(3, prepared.size)
        assertTrue(prepared[0].isFromUser)
        assertEquals(opening, prepared[0].text)
        assertFalse(prepared[1].isFromUser)
        assertEquals("Present po! Ready!", prepared[1].text)
        assertTrue(prepared[2].isFromUser)
        assertEquals("1\n2\n3", prepared[2].text)
    }

    @Test
    fun `preserves opening message even when history exceeds maxTurns`() {
        val opening = "Grand Prize as of today: iPhone 17 Pro Max 512GB"
        val history = mutableListOf(
            ConversationTurn(isFromUser = true, text = opening),
            ConversationTurn(isFromUser = false, text = "Static reply")
        )
        for (i in 1..20) {
            history.add(ConversationTurn(isFromUser = true, text = "Msg $i"))
            history.add(ConversationTurn(isFromUser = false, text = "Reply $i"))
        }
        history.add(ConversationTurn(isFromUser = true, text = "Times up"))

        val prepared = GeminiReplyClient.prepareConversationTurns(
            history = history,
            latestMessage = "Times up",
            openingMessage = opening,
            maxTurns = 10
        )

        assertTrue(prepared.first().isFromUser)
        assertTrue(prepared.first().text.contains(opening))
        assertTrue(prepared.last().isFromUser)
        assertTrue(prepared.last().text.contains("Times up"))
        // Verify strict role alternation across all prepared turns
        for (i in 1 until prepared.size) {
            assertTrue(
                "Adjacent turns at $i should alternate roles",
                prepared[i].isFromUser != prepared[i - 1].isFromUser
            )
        }
    }

    @Test
    fun `effective system prompt instructs Gemini to reread full conversation and anchors opening message`() {
        val opening = "Grand Prize as of today: iPhone 17 Pro Max 512GB"
        val prompt = GeminiReplyClient.buildEffectiveSystemPrompt(
            systemPrompt = "Reply fast and politely.",
            senderName = "JhuDy Marco",
            openingMessage = opening,
            totalTurns = 4
        )

        assertTrue(prompt.contains("Reply fast and politely."))
        assertTrue(prompt.contains("JhuDy Marco"))
        assertTrue(prompt.contains("re-read the entire conversation starting from the very first message"))
        assertTrue(prompt.contains(opening))
    }
}
