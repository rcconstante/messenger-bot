package com.pagerelay.bot

import com.pagerelay.bot.automation.ConversationSessionManager
import com.pagerelay.bot.automation.MessageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionManagerTest {

    @Test
    fun `first message triggers static reply and call while subsequent messages route to gemini`() {
        var now = 1_000L
        val manager = ConversationSessionManager(clock = { now })

        val first = manager.onIncomingMessage(
            conversationKey = "com.facebook.orca|my page|jane doe",
            senderTitle = "Jane Doe",
            messageText = "Hi, do you have openings today?"
        )
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, first.action)
        assertEquals(1, first.sessionInfo.messageCount)
        assertTrue(first.sessionInfo.initialStaticReplySent)

        manager.recordAssistantReply(first.conversationKey, "Thanks for messaging us!")
        manager.markAutoCallTriggered(first.conversationKey)

        now += 3_000L
        val followUp = manager.onIncomingMessage(
            conversationKey = "com.facebook.orca|my page|jane doe",
            senderTitle = "Jane Doe",
            messageText = "Around 3pm would be great."
        )
        assertEquals(MessageAction.FOLLOWUP_GEMINI, followUp.action)
        assertEquals(2, followUp.sessionInfo.messageCount)
        assertEquals(2, followUp.priorTurns.size)
        assertTrue(followUp.priorTurns[0].isFromUser)
        assertEquals("Thanks for messaging us!", followUp.priorTurns[1].text)
    }

    @Test
    fun `suppresses duplicate notifications within dedupe window`() {
        var now = 5_000L
        val manager = ConversationSessionManager(
            clock = { now },
            dedupeWindowMillis = 10_000L
        )

        val first = manager.onIncomingMessage(
            conversationKey = "conv-1",
            senderTitle = "Chris",
            messageText = "Hello there!"
        )
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, first.action)

        now += 2_000L
        val duplicate = manager.onIncomingMessage(
            conversationKey = "conv-1",
            senderTitle = "Chris",
            messageText = "  hello   there! "
        )
        assertEquals(MessageAction.IGNORE_DUPLICATE, duplicate.action)
        assertEquals(1, duplicate.sessionInfo.messageCount)

        // After dedupe window expires, same text is treated as a legitimate follow-up
        now += 15_000L
        val laterMessage = manager.onIncomingMessage(
            conversationKey = "conv-1",
            senderTitle = "Chris",
            messageText = "Hello there!"
        )
        assertEquals(MessageAction.FOLLOWUP_GEMINI, laterMessage.action)
        assertEquals(2, laterMessage.sessionInfo.messageCount)
    }

    @Test
    fun `resetting session allows initial static reply and call to trigger again`() {
        var now = 1_000L
        val manager = ConversationSessionManager(clock = { now })

        val first = manager.onIncomingMessage("conv-reset", "Taylor", "Initial message")
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, first.action)
        assertEquals(1, manager.sessions.value.size)

        manager.resetSession("conv-reset")
        assertEquals(0, manager.sessions.value.size)

        now += 1_000L
        val afterReset = manager.onIncomingMessage("conv-reset", "Taylor", "Initial message")
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, afterReset.action)
        assertEquals(1, afterReset.sessionInfo.messageCount)
    }
}
