package com.pagerelay.bot.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pagerelay.bot.automation.AccessibilityServiceBridge
import com.pagerelay.bot.automation.AutomationCoordinator
import com.pagerelay.bot.automation.LiveAccessibilityTree
import com.pagerelay.bot.automation.MessengerAccessibilityDriver
import com.pagerelay.bot.automation.UiNodeSnapshot

class MessengerCallAccessibilityService : AccessibilityService(), MessengerAccessibilityDriver {

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var lastEventDispatchElapsedMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        AccessibilityServiceBridge.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        AccessibilityServiceBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        AccessibilityServiceBridge.detach(this)
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No audio/speech feedback to interrupt.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg != AutomationCoordinator.MESSENGER_PACKAGE) return

        val listener = AccessibilityServiceBridge.onMessengerWindowUpdated ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventDispatchElapsedMs < WINDOW_EVENT_THROTTLE_MS) return
        lastEventDispatchElapsedMs = now

        val tree = captureActiveTree() ?: return
        listener.invoke(tree)
    }

    override fun isConnected(): Boolean = connected

    override fun getCurrentPackageName(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    override fun captureActiveTree(): LiveAccessibilityTree? {
        if (!connected) return null
        val messengerRoot = findMessengerRootNode() ?: return null
        return LiveAccessibilityTree.capture(messengerRoot)
    }

    override fun clickNode(tree: LiveAccessibilityTree, node: UiNodeSnapshot): Boolean {
        if (!connected) return false
        val liveNode = tree.findClickableLiveNode(node.nodeId) ?: return false
        return try {
            liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Throwable) {
            false
        }
    }

    override fun setNodeText(
        tree: LiveAccessibilityTree,
        node: UiNodeSnapshot,
        text: String
    ): Boolean {
        if (!connected) return false
        val liveNode = tree.resolveLiveNode(node.nodeId) ?: return false
        return try {
            liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
    }

    override fun performBack(): Boolean {
        if (!connected) return false
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {
            false
        }
    }

    private fun findMessengerRootNode(): AccessibilityNodeInfo? {
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == AutomationCoordinator.MESSENGER_PACKAGE) {
                return active
            }
        } catch (_: Throwable) {
            // Fall through to interactive window inspection.
        }

        return try {
            windows?.firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.root?.packageName?.toString() == AutomationCoordinator.MESSENGER_PACKAGE
            }?.root
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val WINDOW_EVENT_THROTTLE_MS = 350L
    }
}
