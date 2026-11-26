package com.pagerelay.bot

import com.pagerelay.bot.ai.ConversationTurn
import com.pagerelay.bot.automation.ConversationSessionManager
import com.pagerelay.bot.automation.MessageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `keeps first message anchored across rapid burst countdown messages even past rolling limit`() {
        var now = 1_000L
        val manager = ConversationSessionManager(clock = { now })
        val key = "com.facebook.orca|giveaway page|jhudy marco"
        val openingMsg = "Grand Prize as of today: iPhone 17 Pro Max 512GB + 200,000 Cash Instant received via PESOnet."

        val first = manager.onIncomingMessage(key, "JhuDy Marco", openingMsg)
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, first.action)
        manager.recordAssistantReply(key, "Mine! Ready to claim!")

        // Simulate 45 rapid burst messages (well past MAX_ROLLING_TURNS = 40)
        for (i in 1..45) {
            now += 1_000L
            val decision = manager.onIncomingMessage(key, "JhuDy Marco", i.toString())
            assertEquals(MessageAction.FOLLOWUP_GEMINI, decision.action)
        }

        val fullContext = manager.getFullConversationContext(key)
        assertEquals(openingMsg, fullContext.openingUserMessage)
        assertTrue(fullContext.turns.isNotEmpty())
        assertEquals(openingMsg, fullContext.turns.first().text)
        assertTrue(fullContext.turns.first().isFromUser)
        assertEquals("Mine! Ready to claim!", fullContext.turns[1].text)
        assertFalse(fullContext.turns[1].isFromUser)
        assertEquals("45", fullContext.turns.last().text)
        assertEquals(ConversationSessionManager.MAX_ROLLING_TURNS, fullContext.turns.size)
    }

    @Test
    fun `backfills missed earlier and batched messages from Android MessagingStyle history`() {
        var now = 1_000L
        val manager = ConversationSessionManager(clock = { now })
        val key = "com.facebook.orca|giveaway page|jhudy marco"
        val openingMsg = "Grand Prize as of today: iPhone 17 Pro Max 512GB + 200,000 Cash"

        // Suppose Android batched message #1 and "1" into a single notification delivery
        val notifHistoryFirst = listOf(
            ConversationTurn(isFromUser = true, text = openingMsg),
            ConversationTurn(isFromUser = true, text = "1")
        )
        val first = manager.onIncomingMessage(
            conversationKey = key,
            senderTitle = "JhuDy Marco",
            messageText = "1",
            notificationHistory = notifHistoryFirst
        )
        assertEquals(MessageAction.FIRST_MESSAGE_STATIC_AND_CALL, first.action)
        manager.recordAssistantReply(key, "Present po!")

        // Later, Android batches "2" and "3" and only notifies on "3"
        now += 2_000L
        val notifHistoryLater = listOf(
            ConversationTurn(isFromUser = true, text = openingMsg),
            ConversationTurn(isFromUser = true, text = "1"),
            ConversationTurn(isFromUser = false, text = "Present po!"),
            ConversationTurn(isFromUser = true, text = "2"),
            ConversationTurn(isFromUser = true, text = "3")
        )
        val followUp = manager.onIncomingMessage(
            conversationKey = key,
            senderTitle = "JhuDy Marco",
            messageText = "3",
            notificationHistory = notifHistoryLater
        )
        assertEquals(MessageAction.FOLLOWUP_GEMINI, followUp.action)

        val fullContext = manager.getFullConversationContext(key)
        assertEquals(openingMsg, fullContext.openingUserMessage)
        assertEquals(
            listOf(
                ConversationTurn(true, openingMsg),
                ConversationTurn(true, "1"),
                ConversationTurn(false, "Present po!"),
                ConversationTurn(true, "2"),
                ConversationTurn(true, "3")
            ),
            fullContext.turns
        )
    }

    @Test
    fun `tracks newer user message sequence to coalesce queued bursts`() {
        var now = 1_000L
        val manager = ConversationSessionManager(clock = { now })
        val key = "conv-burst"

        val d1 = manager.onIncomingMessage(key, "Host", "Prize announcement")
        assertFalse(manager.hasNewerUserMessage(key, d1.messageSequence))

        now += 500L
        val d2 = manager.onIncomingMessage(key, "Host", "1")
        assertTrue(manager.hasNewerUserMessage(key, d1.messageSequence))
        assertFalse(manager.hasNewerUserMessage(key, d2.messageSequence))

        now += 500L
        val d3 = manager.onIncomingMessage(key, "Host", "2")
        assertTrue(manager.hasNewerUserMessage(key, d2.messageSequence))
        assertFalse(manager.hasNewerUserMessage(key, d3.messageSequence))
    }
}
