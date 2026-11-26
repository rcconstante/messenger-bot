package com.pagerelay.bot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pagerelay.bot.domain.EmulatorProfile
import com.pagerelay.bot.ui.MainUiState
import com.pagerelay.bot.ui.components.ExperimentalNoticeBanner
import com.pagerelay.bot.ui.components.SectionCard
import com.pagerelay.bot.ui.components.StatusPill
import com.pagerelay.bot.ui.components.SyncedOutlinedTextField
import com.pagerelay.bot.ui.components.formatDuration
import com.pagerelay.bot.ui.components.formatLogTimestamp
import com.pagerelay.bot.ui.theme.MonoTimestampStyle
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import kotlin.math.roundToInt

private val CooldownOptionsSeconds = listOf(30, 60, 90, 180, 300)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoCallScreen(
    uiState: MainUiState,
    onSetAutoCallEnabled: (Boolean) -> Unit,
    onUpdateSecondaryAccountName: (String) -> Unit,
    onStartRecipientCapture: () -> Unit,
    onCancelRecipientCapture: () -> Unit,
    onClearSavedRecipient: () -> Unit,
    onOpenMessenger: () -> Unit,
    onUpdateMaxCallDuration: (Int) -> Unit,
    onSetAutoEndCallEnabled: (Boolean) -> Unit,
    onUpdateDuplicateCooldown: (Int) -> Unit,
    onUpdateEmulatorProfile: (EmulatorProfile) -> Unit,
    onRunDryRun: () -> Unit,
    onRunLiveTestCall: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
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
        // 1. Experimental Notice Banner
        item(key = "experimental_banner") {
            ExperimentalNoticeBanner()
        }

        // 2. Master Automatic Messenger Calling Toggle
        item(key = "master_auto_call") {
            SectionCard(
                title = "Automatic Messenger Calling",
                subtitle = "Alerts your secondary Messenger account after a new Page message",
                icon = Icons.Rounded.PhoneInTalk,
                trailingHeader = {
                    Switch(
                        checked = settings.autoCallEnabled,
                        onCheckedChange = onSetAutoCallEnabled
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = "Messenger Audio Calls Only",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = Icons.Rounded.PhoneInTalk
                    )
                    StatusPill(
                        text = if (live.isMessengerInstalled) "Messenger Installed" else "Messenger Missing",
                        containerColor = if (live.isMessengerInstalled) semantic.successContainer else semantic.emergencyContainer,
                        contentColor = if (live.isMessengerInstalled) semantic.onSuccessContainer else semantic.onEmergencyContainer
                    )
                }
                Text(
                    text = "PageRelay strictly targets the Messenger audio call button and explicitly avoids video call controls. " +
                        "Auto-call runs on the first incoming message of a conversation after the instant static reply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 3. Secondary Messenger Account & Capture Verification
        item(key = "secondary_account") {
            SectionCard(
                title = "Secondary Messenger Account",
                subtitle = "Strict identity verification ensures calls only go to this account",
                icon = Icons.Rounded.PersonSearch
            ) {
                SyncedOutlinedTextField(
                    externalValue = settings.secondaryAccountName,
                    onValueChange = onUpdateSecondaryAccountName,
                    label = "Secondary Messenger Account Name",
                    placeholder = "e.g. Alex Operations",
                    supportingText = "Enter the exact display name shown in the Messenger chat header."
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "Save & Verify Recipient Conversation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                when {
                    live.isCaptureModeActive -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Listening for Messenger Chat Header...",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Switch to Facebook Messenger and open the exact conversation with your secondary account. " +
                                        "PageRelay will capture its verified signature automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                                )
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
                                        onClick = onCancelRecipientCapture,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Cancel Capture")
                                    }
                                }
                            }
                        }
                    }

                    settings.savedRecipient.isCaptured -> {
                        val saved = settings.savedRecipient
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = semantic.successContainer,
                            border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VerifiedUser,
                                        contentDescription = null,
                                        tint = semantic.onSuccessContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Verified Recipient Captured",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = semantic.onSuccessContainer
                                    )
                                }
                                Text(
                                    text = saved.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = semantic.onSuccessContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                if (saved.secondaryLabel.isNotBlank()) {
                                    Text(
                                        text = "Subtitle: ${saved.secondaryLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = semantic.onSuccessContainer.copy(alpha = 0.9f)
                                    )
                                }
                                Text(
                                    text = "Captured ${formatLogTimestamp(saved.capturedAtMillis)}",
                                    style = MonoTimestampStyle,
                                    color = semantic.onSuccessContainer.copy(alpha = 0.85f)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            onStartRecipientCapture()
                                            onOpenMessenger()
                                        },
                                        enabled = live.isAccessibilityServiceConnected,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text("Re-capture", modifier = Modifier.padding(start = 4.dp))
                                    }
                                    OutlinedButton(
                                        onClick = onClearSavedRecipient,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text("Clear", modifier = Modifier.padding(start = 4.dp))
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Not captured yet — open Messenger to your secondary account chat and capture its verified signature so PageRelay aborts if a wrong chat opens.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(
                                    onClick = {
                                        onStartRecipientCapture()
                                        onOpenMessenger()
                                    },
                                    enabled = live.isAccessibilityServiceConnected && live.isMessengerInstalled,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PersonSearch,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Capture from Messenger",
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                                if (!live.isAccessibilityServiceConnected) {
                                    Text(
                                        text = "Enable PageRelay Accessibility Service first to capture directly from Messenger.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = semantic.warning
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Call Duration & Safety Controls
        item(key = "duration_safety") {
            SectionCard(
                title = "Call Duration & Safety Controls",
                subtitle = "Prevent runaway calls, duplicate rings, and emulator timing misses",
                icon = Icons.Rounded.Tune
            ) {
                // Max call duration slider (15s to 300s stepped by 15s)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Maximum Call Duration",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        StatusPill(
                            text = formatDuration(settings.maxCallDurationSeconds),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Slider(
                        value = settings.maxCallDurationSeconds.coerceIn(15, 300).toFloat(),
                        onValueChange = { raw ->
                            val snapped = ((raw / 15f).roundToInt() * 15).coerceIn(15, 300)
                            if (snapped != settings.maxCallDurationSeconds) {
                                onUpdateMaxCallDuration(snapped)
                            }
                        },
                        valueRange = 15f..300f,
                        steps = 18
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "15s",
                            style = MonoTimestampStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "5m 00s",
                            style = MonoTimestampStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Auto-end call toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "End call automatically after max duration",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically locates and taps Messenger's End Call button when the limit is reached.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoEndCallEnabled,
                        onCheckedChange = onSetAutoEndCallEnabled
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Duplicate-call prevention cooldown chips
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Duplicate-Call Prevention Cooldown",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "Blocks back-to-back call triggers within this cooldown window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CooldownOptionsSeconds.forEach { seconds ->
                            val selected = settings.duplicateCallCooldownSeconds == seconds
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateDuplicateCooldown(seconds) },
                                label = { Text(formatDuration(seconds)) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Emulator compatibility profile
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Emulator & Device Timing Profile",
                        style = MaterialTheme.typography.titleSmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EmulatorProfile.entries.forEach { profile ->
                            val selected = settings.emulatorProfile == profile
                            val chipIcon = if (profile == EmulatorProfile.PHYSICAL_DEVICE) {
                                Icons.Rounded.PhoneAndroid
                            } else {
                                Icons.Rounded.Computer
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateEmulatorProfile(profile) },
                                label = { Text(profile.displayName) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = chipIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = settings.emulatorProfile.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Step delay: ${settings.emulatorProfile.stepDelayMs}ms • " +
                                    "Launch wait: ${settings.emulatorProfile.launchWaitMs}ms • " +
                                    "Node timeout: ${settings.emulatorProfile.nodePollTimeoutMs}ms",
                                style = MonoTimestampStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 5. Test Messenger Call Card
        item(key = "test_messenger_call") {
            SectionCard(
                title = "Test Messenger Call",
                subtitle = "Run a safe dry-run or live audio call test anytime",
                icon = Icons.Rounded.FactCheck
            ) {
                Text(
                    text = "Dry-Run Verification launches Messenger, navigates to your secondary chat, verifies identity, " +
                        "and confirms the Audio Call button is present WITHOUT placing a call. " +
                        "Test Live Audio Call runs the full audio-only flow using your duration & auto-end settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onRunDryRun,
                        enabled = uiState.canTestMessengerCall,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FactCheck,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Dry-Run Verification (No Call Placed)",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Button(
                        onClick = onRunLiveTestCall,
                        enabled = uiState.canTestMessengerCall,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhoneInTalk,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Test Live Audio Call (${formatDuration(settings.maxCallDurationSeconds)})",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                if (!uiState.canTestMessengerCall) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val reason = when {
                        settings.emergencyStopped -> "Emergency Stop is active. Re-arm PageRelay first."
                        live.isCallInProgress -> "A Messenger call flow is already in progress."
                        !live.isAccessibilityServiceConnected -> "Accessibility Service is not enabled yet."
                        !live.isMessengerInstalled -> "Official Facebook Messenger is not installed on this device."
                        !uiState.isRecipientConfigured -> "Enter or capture a secondary Messenger account name above."
                        else -> "Complete setup to run Messenger tests."
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.warning,
                            modifier = Modifier.weight(1f)
                        )
                        if (!live.isAccessibilityServiceConnected) {
                            OutlinedButton(
                                onClick = onOpenAccessibilitySettings,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Enable", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
