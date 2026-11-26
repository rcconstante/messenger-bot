package com.pagerelay.bot.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.pagerelay.bot.PageRelayApplication
import com.pagerelay.bot.ai.ConversationTurn
import com.pagerelay.bot.automation.IncomingPageMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class PageNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        (application as? PageRelayApplication)
            ?.automationCoordinator
            ?.onNotificationListenerConnectionChanged(true)
    }

    override fun onListenerDisconnected() {
        isServiceConnected = false
        (application as? PageRelayApplication)
            ?.automationCoordinator
            ?.onNotificationListenerConnectionChanged(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        if (pkg !in ALLOWED_PACKAGES) return

        val notification = sbn.notification ?: return
        val flags = notification.flags
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val replySpec = findInlineReplyAction(notification) ?: return
        val extracted = extractMessagePayload(sbn, notification) ?: return

        val incoming = IncomingPageMessage(
            notificationKey = sbn.key,
            conversationKey = extracted.conversationKey,
            packageName = pkg,
            title = extracted.title,
            conversationTitle = extracted.conversationTitle,
            subText = extracted.subText,
            senderName = extracted.senderName,
            messageText = extracted.messageText,
            receivedAtMillis = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            notificationHistory = extracted.notificationHistory,
            replyAction = { replyText ->
                dispatchInlineReply(replySpec, replyText)
            }
        )

        (application as? PageRelayApplication)
            ?.automationCoordinator
            ?.onIncomingNotificationMessage(incoming)
    }

    private data class InlineReplySpec(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val primaryResultKey: String
    )

    private data class ExtractedNotificationData(
        val conversationKey: String,
        val title: String,
        val conversationTitle: String?,
        val subText: String?,
        val senderName: String,
        val messageText: String,
        val notificationHistory: List<ConversationTurn>
    )

    private fun findInlineReplyAction(notification: Notification): InlineReplySpec? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs
            val intent = action.actionIntent
            if (inputs.isNullOrEmpty() || intent == null) continue
            val freeFormInput = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.first()
            return InlineReplySpec(
                actionIntent = intent,
                remoteInputs = inputs,
                primaryResultKey = freeFormInput.resultKey
            )
        }
        return null
    }

    private fun extractMessagePayload(
        sbn: StatusBarNotification,
        notification: Notification
    ): ExtractedNotificationData? {
        val extras = notification.extras ?: Bundle.EMPTY
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val messagingStyle = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        } catch (_: Throwable) {
            null
        }

        val selfUser = messagingStyle?.user
        val styledMessages = buildList {
            if (messagingStyle != null) {
                addAll(messagingStyle.historicMessages)
                addAll(messagingStyle.messages)
            }
        }.filter { !it.text.isNullOrBlank() }

        // If Messenger re-posted the notification immediately after OUR inline reply,
        // the newest MessagingStyle message belongs to selfUser (or null person). Ignore self-echoes.
        val latestStyled = styledMessages.lastOrNull()
        if (latestStyled != null && isSelfMessagingStyleMessage(latestStyled, selfUser)) {
            return null
        }

        val notificationHistory = buildNotificationHistory(
            styledMessages = styledMessages,
            selfUser = selfUser,
            extras = extras
        )

        val senderFromStyle = latestStyled?.person?.name?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val textFromStyle = latestStyled?.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val fallbackText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val messageText = textFromStyle ?: fallbackText ?: return null
        val senderName = senderFromStyle ?: rawTitle.ifBlank { "Facebook User" }
        val conversationTitle = rawConversationTitle
            ?: messagingStyle?.conversationTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val stableKey = buildConversationKey(
            packageName = sbn.packageName,
            conversationTitle = conversationTitle,
            senderName = senderName,
            fallbackKey = sbn.key
        )

        return ExtractedNotificationData(
            conversationKey = stableKey,
            title = rawTitle.ifBlank { senderName },
            conversationTitle = conversationTitle,
            subText = rawSubText,
            senderName = senderName,
            messageText = messageText,
            notificationHistory = notificationHistory
        )
    }

    private fun isSelfMessagingStyleMessage(
        message: NotificationCompat.MessagingStyle.Message,
        selfUser: Person?
    ): Boolean {
        val sender = message.person ?: return true
        if (selfUser == null) return false

        val selfKey = selfUser.key?.trim()?.takeIf { it.isNotEmpty() }
        val senderKey = sender.key?.trim()?.takeIf { it.isNotEmpty() }
        if (selfKey != null && senderKey != null) {
            return selfKey == senderKey
        }

        val selfName = selfUser.name?.toString()?.trim()?.lowercase(Locale.ROOT)
        val senderName = sender.name?.toString()?.trim()?.lowercase(Locale.ROOT)
        if (!selfName.isNullOrBlank() && !senderName.isNullOrBlank() && selfName == senderName) {
            return true
        }
        return false
    }

    private fun buildNotificationHistory(
        styledMessages: List<NotificationCompat.MessagingStyle.Message>,
        selfUser: Person?,
        extras: Bundle
    ): List<ConversationTurn> {
        if (styledMessages.isNotEmpty()) {
            return styledMessages.mapNotNull { msg ->
                val text = msg.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val isSelf = isSelfMessagingStyleMessage(msg, selfUser)
                ConversationTurn(
                    isFromUser = !isSelf,
                    text = text
                )
            }
        }

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { line -> line?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        if (textLines.size > 1) {
            return textLines.map { line ->
                ConversationTurn(isFromUser = true, text = line)
            }
        }

        return emptyList()
    }

    private fun buildConversationKey(
        packageName: String,
        conversationTitle: String?,
        senderName: String,
        fallbackKey: String
    ): String {
        val normConv = conversationTitle?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normSender = senderName.trim().lowercase(Locale.ROOT)
        return if (normSender.isNotBlank() || normConv.isNotBlank()) {
            "$packageName|$normConv|$normSender"
        } else {
            fallbackKey
        }
    }

    private suspend fun dispatchInlineReply(
        replySpec: InlineReplySpec,
        replyText: String
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val fillInIntent = Intent()
            val resultsBundle = Bundle().apply {
                putCharSequence(replySpec.primaryResultKey, replyText)
            }
            RemoteInput.addResultsToIntent(replySpec.remoteInputs, fillInIntent, resultsBundle)
            replySpec.actionIntent.send(applicationContext, 0, fillInIntent)
            Result.success(Unit)
        } catch (ce: PendingIntent.CanceledException) {
            Result.failure(IllegalStateException("Notification reply action expired before sending.", ce))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("Failed to send inline notification reply: ${t.message}", t))
        }
    }

    companion object {
        val ALLOWED_PACKAGES: Set<String> = setOf(
            "com.facebook.orca",
            "com.facebook.pages.app"
        )

        @Volatile
        var isServiceConnected: Boolean = false
            private set
    }
}
