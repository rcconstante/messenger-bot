package com.pagerelay.bot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pagerelay.bot.domain.AppSettings
import com.pagerelay.bot.domain.ConversationSessionInfo
import com.pagerelay.bot.ui.GeminiTestState
import com.pagerelay.bot.ui.MainUiState
import com.pagerelay.bot.ui.components.SectionCard
import com.pagerelay.bot.ui.components.StatusPill
import com.pagerelay.bot.ui.components.SyncedOutlinedTextField
import com.pagerelay.bot.ui.components.formatShortTimestamp
import com.pagerelay.bot.ui.theme.MonoTimestampStyle
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CallRinging02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.MailReply01
import me.rerere.hugeicons.stroke.MessageDone01
import me.rerere.hugeicons.stroke.MessageMultiple02
import me.rerere.hugeicons.stroke.Reload
import me.rerere.hugeicons.stroke.ShieldCheck
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOffSlash

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepliesAiScreen(
    uiState: MainUiState,
    onUpdateTargetPageName: (String) -> Unit,
    onUpdateStaticReplyMessage: (String) -> Unit,
    onSetGeminiEnabled: (Boolean) -> Unit,
    onUpdateGeminiApiKey: (String) -> Unit,
    onUpdateGeminiModel: (String) -> Unit,
    onUpdateGeminiSystemPrompt: (String) -> Unit,
    onTestGeminiConnection: () -> Unit,
    onResetConversationSession: (String) -> Unit,
    onClearAllSessions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val semantic = PageRelayThemeExtras.semantic
    var showApiKey by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. No Facebook Login Needed Reassurance Card
        item(key = "no_login_reassurance") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = semantic.successContainer,
                border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(semantic.success.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.ShieldCheck,
                            contentDescription = null,
                            tint = semantic.onSuccessContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No Facebook Login Needed",
                            style = MaterialTheme.typography.titleSmall,
                            color = semantic.onSuccessContainer
                        )
                        Text(
                            text = "Uses Android Notification Inline Reply — PageRelay never asks for your Facebook password, tokens, or browser cookies.",
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.onSuccessContainer.copy(alpha = 0.92f)
                        )
                    }
                }
            }
        }

        // 2. Target Facebook Page & Instant Static Reply
        item(key = "page_and_static_reply") {
            SectionCard(
                title = "Target Facebook Page & Static Reply",
                subtitle = "Sent instantly on the first incoming message of a conversation",
                icon = HugeIcons.MailReply01
            ) {
                SyncedOutlinedTextField(
                    externalValue = settings.targetPageName,
                    onValueChange = onUpdateTargetPageName,
                    label = "Target Facebook Page Name",
                    placeholder = "e.g. Sunrise Bakery Support",
                    supportingText = "Matched against incoming Facebook / Business Suite / Messenger notifications."
                )

                SyncedOutlinedTextField(
                    externalValue = settings.staticReplyMessage,
                    onValueChange = onUpdateStaticReplyMessage,
                    label = "Instant Predefined Static Reply",
                    placeholder = "Thanks for messaging us! Our team is on the way.",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    supportingText = "Delivered immediately via Notification Inline Reply before triggering the audio call."
                )

                // Live Chat-Bubble Preview
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "LIVE REPLY PREVIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 16.dp,
                                    bottomEnd = 4.dp
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = settings.targetPageName.ifBlank { "Your Facebook Page" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = settings.staticReplyMessage.ifBlank { "(Enter a static reply message above)" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Gemini AI Subsequent Replies
        item(key = "gemini_ai") {
            SectionCard(
                title = "Gemini AI Follow-Up Replies",
                subtitle = "Optional AI replies for message #2 and onward in the same thread",
                icon = HugeIcons.Sparkles,
                trailingHeader = {
                    Switch(
                        checked = settings.geminiEnabled,
                        onCheckedChange = onSetGeminiEnabled
                    )
                }
            ) {
                Text(
                    text = "Message #1 always triggers your Static Reply and Messenger Auto-Call. " +
                        "Before every 2nd, 3rd, and subsequent reply, Gemini re-reads the full conversation starting from Message #1 " +
                        "(including rapid burst messages) so it always keeps the original context.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SyncedOutlinedTextField(
                    externalValue = settings.geminiApiKey,
                    onValueChange = onUpdateGeminiApiKey,
                    label = "Gemini API Key",
                    placeholder = "AIza...",
                    leadingIcon = {
                        Icon(
                            imageVector = HugeIcons.Key01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) HugeIcons.ViewOffSlash else HugeIcons.View,
                                contentDescription = if (showApiKey) "Hide API key" else "Show API key"
                            )
                        }
                    },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = "Stored locally in DataStore on this device only."
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Gemini Model",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppSettings.SUPPORTED_GEMINI_MODELS.forEach { model ->
                            val selected = settings.geminiModel == model
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateGeminiModel(model) },
                                label = { Text(model) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = HugeIcons.CheckmarkCircle02,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                    SyncedOutlinedTextField(
                        externalValue = settings.geminiModel,
                        onValueChange = onUpdateGeminiModel,
                        label = "Active Model ID (or custom)",
                        placeholder = AppSettings.DEFAULT_GEMINI_MODEL,
                        supportingText = "Tap a preset above or enter any Gemini model ID from Google AI Studio."
                    )
                }

                SyncedOutlinedTextField(
                    externalValue = settings.geminiSystemPrompt,
                    onValueChange = onUpdateGeminiSystemPrompt,
                    label = "System Instructions",
                    placeholder = "Describe your Page's tone, hours, and escalation policy...",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isTesting = uiState.geminiTestState is GeminiTestState.Testing
                    FilledTonalButton(
                        onClick = onTestGeminiConnection,
                        enabled = !isTesting && settings.geminiApiKey.isNotBlank(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing...")
                        } else {
                            Icon(
                                imageVector = HugeIcons.Sparkles,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Test Gemini Connection",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }

                when (val testState = uiState.geminiTestState) {
                    is GeminiTestState.Completed -> {
                        val ok = testState.result.success
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (ok) semantic.successContainer else semantic.emergencyContainer,
                            border = BorderStroke(
                                1.dp,
                                if (ok) semantic.success.copy(alpha = 0.4f) else semantic.emergency.copy(alpha = 0.4f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (ok) HugeIcons.CheckmarkCircle02 else HugeIcons.AlertCircle,
                                    contentDescription = null,
                                    tint = if (ok) semantic.onSuccessContainer else semantic.onEmergencyContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = testState.result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) semantic.onSuccessContainer else semantic.onEmergencyContainer
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // 4. Active Conversation Sessions
        item(key = "active_sessions") {
            SectionCard(
                title = "Active Conversation Sessions",
                subtitle = "Reset a session to re-test the initial static reply and call trigger",
                icon = HugeIcons.MessageMultiple02,
                trailingHeader = {
                    if (uiState.activeSessions.isNotEmpty()) {
                        TextButton(onClick = onClearAllSessions) {
                            Icon(
                                imageVector = HugeIcons.Delete02,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Clear All", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            ) {
                if (uiState.activeSessions.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = HugeIcons.MessageMultiple02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "No tracked conversations yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "When a message arrives for your target Page, PageRelay tracks its reply and call state here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        uiState.activeSessions.forEachIndexed { index, session ->
                            SessionItemCard(
                                session = session,
                                onReset = { onResetConversationSession(session.conversationKey) }
                            )
                            if (index < uiState.activeSessions.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionItemCard(
    session: ConversationSessionInfo,
    onReset: () -> Unit
) {
    val semantic = PageRelayThemeExtras.semantic
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.senderTitle.ifBlank { "Conversation" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("Updated ${formatShortTimestamp(session.lastUpdatedMillis)} • ${session.messageCount} msg(s)")
                        if (session.contextTurnCount > 0) {
                            append(" • ${session.contextTurnCount} turns in context")
                        }
                    },
                    style = MonoTimestampStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = HugeIcons.Reload,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        if (session.openingMessagePreview.isNotBlank() && session.openingMessagePreview != session.lastMessagePreview) {
            Text(
                text = "1st msg: \"${session.openingMessagePreview}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (session.lastMessagePreview.isNotBlank()) {
            Text(
                text = "Latest: \"${session.lastMessagePreview}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (session.initialStaticReplySent) {
                StatusPill(
                    text = "Static Reply Sent",
                    containerColor = semantic.successContainer,
                    contentColor = semantic.onSuccessContainer,
                    icon = HugeIcons.MessageDone01
                )
            }
            if (session.autoCallTriggered) {
                StatusPill(
                    text = "Call Triggered",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = HugeIcons.CallRinging02
                )
            }
        }
    }
}
