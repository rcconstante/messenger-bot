package com.pagerelay.bot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pagerelay.bot.domain.PageRelayAppProvider

class EmergencyStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != null && intent.action != ACTION_EMERGENCY_STOP) return
        val provider = context.applicationContext as? PageRelayAppProvider ?: return
        provider.container.automationController.triggerEmergencyStop(
            reason = "Emergency Stop triggered from notification"
        )
    }

    companion object {
        const val ACTION_EMERGENCY_STOP = "com.pagerelay.bot.action.EMERGENCY_STOP"
    }
}
