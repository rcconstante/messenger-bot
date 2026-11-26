package com.pagerelay.bot.automation

import com.pagerelay.bot.ai.ConversationTurn

/**
 * Decoupled bridge between the AccessibilityService lifecycle and AutomationCoordinator.
 */
interface MessengerAccessibilityDriver {
    fun isConnected(): Boolean
    fun captureActiveTree(): LiveAccessibilityTree?
    fun clickNode(tree: LiveAccessibilityTree, node: UiNodeSnapshot): Boolean
    fun setNodeText(tree: LiveAccessibilityTree, node: UiNodeSnapshot, text: String): Boolean
    fun performBack(): Boolean
    fun getCurrentPackageName(): String?
}

object AccessibilityServiceBridge {
    @Volatile
    var driver: MessengerAccessibilityDriver? = null
        private set

    @Volatile
    var onServiceConnectionChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var onMessengerWindowUpdated: ((LiveAccessibilityTree) -> Unit)? = null

    fun attach(newDriver: MessengerAccessibilityDriver) {
        driver = newDriver
        onServiceConnectionChanged?.invoke(true)
    }

    fun detach(existingDriver: MessengerAccessibilityDriver) {
        if (driver === existingDriver) {
            driver = null
            onServiceConnectionChanged?.invoke(false)
        }
    }

    fun isConnected(): Boolean = driver?.isConnected() == true
}

data class IncomingPageMessage(
    val notificationKey: String,
    val conversationKey: String,
    val packageName: String,
    val title: String,
    val conversationTitle: String?,
    val subText: String?,
    val senderName: String,
    val messageText: String,
    val receivedAtMillis: Long,
    val notificationHistory: List<ConversationTurn> = emptyList(),
    val replyAction: suspend (String) -> Result<Unit>
)
