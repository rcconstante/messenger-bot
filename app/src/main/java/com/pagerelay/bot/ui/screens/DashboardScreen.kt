package com.pagerelay.bot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pagerelay.bot.ui.MainUiState
import com.pagerelay.bot.ui.components.PipelineStepperCard
import com.pagerelay.bot.ui.components.ReadinessChecklistItem
import com.pagerelay.bot.ui.components.SectionCard
import com.pagerelay.bot.ui.components.StatusPill
import com.pagerelay.bot.ui.components.formatDuration
import com.pagerelay.bot.ui.components.formatShortTimestamp
import com.pagerelay.bot.ui.theme.CrimsonStop
import com.pagerelay.bot.ui.theme.MonoTimestampStyle
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Call02
import me.rerere.hugeicons.stroke.CallRinging02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.PauseCircle
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.ShieldEnergy
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TaskDone01
import me.rerere.hugeicons.stroke.UserSearch01

@Composable
fun DashboardScreen(
    uiState: MainUiState,
    onToggleAutomation: (Boolean) -> Unit,
    onEmergencyStop: () -> Unit,
    onRearm: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenMessenger: () -> Unit,
    onCancelCapture: () -> Unit,
    onNavigateToReplies: () -> Unit,
    onNavigateToAutoCall: () -> Unit,
    onRunDryRun: () -> Unit,
    onRunLiveTestCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val live = uiState.liveState
    val semantic = PageRelayThemeExtras.semantic

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Hero Status Card
        item(key = "hero_status") {
            val statusTitle: String
            val statusBadgeText: String
            val statusBadgeBg: Color
            val statusBadgeFg: Color
            val statusIcon = when {
                settings.emergencyStopped -> HugeIcons.Alert02
                live.isCallInProgress -> HugeIcons.CallRinging02
                settings.automationEnabled -> HugeIcons.ShieldEnergy
                else -> HugeIcons.PauseCircle
            }

            when {
                settings.emergencyStopped -> {
                    statusTitle = "Emergency Stopped"
                    statusBadgeText = "Locked Off"
                    statusBadgeBg = semantic.emergencyContainer
                    statusBadgeFg = semantic.onEmergencyContainer
                }
                live.isCallInProgress -> {
                    statusTitle = "Messenger Call Active"
                    statusBadgeText = live.currentCallStatus?.label ?: "In Progress"
                    statusBadgeBg = MaterialTheme.colorScheme.primaryContainer
                    statusBadgeFg = MaterialTheme.colorScheme.onPrimaryContainer
                }
                settings.automationEnabled -> {
                    statusTitle = "Armed & Monitoring"
                    statusBadgeText = "Active"
                    statusBadgeBg = semantic.successContainer
                    statusBadgeFg = semantic.onSuccessContainer
                }
                else -> {
                    statusTitle = "Standing By"
                    statusBadgeText = "Paused"
                    statusBadgeBg = MaterialTheme.colorScheme.surfaceVariant
                    statusBadgeFg = MaterialTheme.colorScheme.onSurfaceVariant
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(statusBadgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusBadgeFg,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            StatusPill(
                                text = statusBadgeText,
                                containerColor = statusBadgeBg,
                                contentColor = statusBadgeFg
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Switch(
                            checked = settings.automationEnabled && !settings.emergencyStopped,
                            onCheckedChange = onToggleAutomation
                        )
                    }

                    Text(
                        text = live.currentStepMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (live.lastTriggeredPage != null && live.lastTriggeredAtMillis != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Last trigger: ${live.lastTriggeredPage}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatShortTimestamp(live.lastTriggeredAtMillis),
                                    style = MonoTimestampStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (settings.emergencyStopped) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onRearm(false) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Clear Stop")
                            }
                            Button(
                                onClick = { onRearm(true) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Play,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Re-arm Now",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = onEmergencyStop,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonStop,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(vertical = 13.dp)
                        ) {
                            Icon(
                                imageVector = HugeIcons.StopCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "EMERGENCY STOP",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Live Active Call or Recipient Capture Banner
        if (live.isCallInProgress) {
            item(key = "live_call_banner") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = HugeIcons.CallRinging02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column {
                                    Text(
                                        text = live.currentCallStatus?.label ?: "Running Messenger Call",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Target: ${settings.effectiveRecipientName.ifBlank { "Unset" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            StatusPill(
                                text = formatDuration(live.currentCallElapsedSeconds),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Button(
                            onClick = onEmergencyStop,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonStop,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Abort Active Call Immediately")
                        }
                    }
                }
            }
        }

        if (live.isCaptureModeActive) {
            item(key = "live_capture_banner") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = HugeIcons.UserSearch01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Recipient Capture Active",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Open your secondary account chat in Messenger. PageRelay will save its verified header automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                                )
                            }
                        }
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onOpenMessenger,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open Messenger")
                            }
                            OutlinedButton(
                                onClick = onCancelCapture,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        // 3. Quick Setup Checklist
        item(key = "setup_checklist") {
            val readyCount = uiState.readinessCount
            SectionCard(
                title = "Quick Setup Checklist",
                subtitle = "Complete these 4 steps once to arm PageRelay",
                icon = HugeIcons.SecurityCheck,
                trailingHeader = {
                    StatusPill(
                        text = "$readyCount / 4 Ready",
                        containerColor = if (readyCount == 4) semantic.successContainer else semantic.warningContainer,
                        contentColor = if (readyCount == 4) semantic.onSuccessContainer else semantic.onWarningContainer,
                        icon = if (readyCount == 4) HugeIcons.CheckmarkCircle02 else null
                    )
                }
            ) {
                ReadinessChecklistItem(
                    title = "1. Notification Access",
                    subtitle = if (live.isNotificationListenerConnected) {
                        "Connected — watching incoming Page notifications"
                    } else {
                        "Required to detect Facebook Page messages & send inline replies"
                    },
                    isReady = live.isNotificationListenerConnected,
                    actionLabel = if (live.isNotificationListenerConnected) "Manage" else "Enable",
                    onAction = onOpenNotificationSettings
                )

                ReadinessChecklistItem(
                    title = "2. Accessibility Service",
                    subtitle = if (live.isAccessibilityServiceConnected) {
                        "Connected — ready for Messenger audio-call automation"
                    } else {
                        "Required to locate your secondary chat & tap Messenger Audio Call"
                    },
                    isReady = live.isAccessibilityServiceConnected,
                    actionLabel = if (live.isAccessibilityServiceConnected) "Manage" else "Enable",
                    onAction = onOpenAccessibilitySettings
                )

                ReadinessChecklistItem(
                    title = "3. Target Page & Static Reply",
                    subtitle = if (uiState.isPageConfigured) {
                        "Page: \"${settings.targetPageName}\" • Instant reply ready"
                    } else {
                        "Enter your Facebook Page name and instant static reply message"
                    },
                    isReady = uiState.isPageConfigured,
                    actionLabel = "Configure",
                    onAction = onNavigateToReplies
                )

                ReadinessChecklistItem(
                    title = "4. Secondary Messenger Account",
                    subtitle = when {
                        !live.isMessengerInstalled ->
                            "Official Facebook Messenger (com.facebook.orca) not detected"
                        settings.savedRecipient.isCaptured ->
                            "Verified recipient: ${settings.savedRecipient.displayName}"
                        settings.secondaryAccountName.isNotBlank() ->
                            "Account: ${settings.secondaryAccountName} (capture chat to lock identity)"
                        else ->
                            "Set the secondary Messenger account to alert via audio call"
                    },
                    isReady = uiState.isRecipientConfigured && live.isMessengerInstalled,
                    actionLabel = "Set Up",
                    onAction = onNavigateToAutoCall
                )
            }
        }

        // 4. Quick Test Actions
        item(key = "quick_actions") {
            SectionCard(
                title = "Quick Messenger Verification",
                subtitle = "Safely verify Messenger navigation before relying on live alerts",
                icon = HugeIcons.Call02
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRunDryRun,
                        enabled = uiState.canTestMessengerCall,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.TaskDone01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Dry-Run Verify",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }

                    Button(
                        onClick = onRunLiveTestCall,
                        enabled = uiState.canTestMessengerCall,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.CallRinging02,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Test Audio Call",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }

                if (!uiState.canTestMessengerCall) {
                    val hint = when {
                        settings.emergencyStopped -> "Re-arm PageRelay to run verification tests."
                        live.isCallInProgress -> "A Messenger automation task is currently running."
                        !live.isAccessibilityServiceConnected -> "Enable the Accessibility Service in step 2 above to run tests."
                        !live.isMessengerInstalled -> "Install official Facebook Messenger on this device/emulator first."
                        else -> "Configure your secondary Messenger account in the Auto-Call tab."
                    }
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Visual 9-Step Execution Flow
        item(key = "execution_flow") {
            PipelineStepperCard(
                stepStates = live.pipelineSteps,
                currentStepMessage = live.currentStepMessage,
                initiallyExpanded = true
            )
        }
    }
}
