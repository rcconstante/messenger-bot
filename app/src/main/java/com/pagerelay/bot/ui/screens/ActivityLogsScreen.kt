package com.pagerelay.bot.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pagerelay.bot.domain.AuditLogEntry
import com.pagerelay.bot.domain.CallObservableStatus
import com.pagerelay.bot.domain.LogEventType
import com.pagerelay.bot.ui.LogFilter
import com.pagerelay.bot.ui.MainUiState
import com.pagerelay.bot.ui.components.StatusPill
import com.pagerelay.bot.ui.components.formatDuration
import com.pagerelay.bot.ui.components.formatLogTimestamp
import com.pagerelay.bot.ui.theme.MonoDiagnosticStyle
import com.pagerelay.bot.ui.theme.MonoTimestampStyle
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.CallRinging02
import me.rerere.hugeicons.stroke.Clock04
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.MailReply01
import me.rerere.hugeicons.stroke.Notification03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Timer02
import me.rerere.hugeicons.stroke.UserSearch01

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityLogsScreen(
    uiState: MainUiState,
    onSelectFilter: (LogFilter) -> Unit,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearAllLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val semantic = PageRelayThemeExtras.semantic

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Activity Logs?") },
            text = {
                Text("This permanently removes all ${uiState.totalEventsCount} recorded audit events from local storage.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAllLogs()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Summary Metrics Strip
        item(key = "metrics_strip") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricTile(
                    label = "Total Events",
                    value = uiState.totalEventsCount.toString(),
                    accentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Call Attempts",
                    value = uiState.callAttemptsCount.toString(),
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Errors",
                    value = uiState.errorCount.toString(),
                    accentColor = if (uiState.errorCount > 0) semantic.emergency else semantic.success,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Filter Chips & Export/Clear Controls
        item(key = "filter_and_actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.selectedLogFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onCopyLogs,
                        enabled = uiState.allLogs.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Copy",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }

                    FilledTonalButton(
                        onClick = onShareLogs,
                        enabled = uiState.allLogs.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Share",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = uiState.allLogs.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete02,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        // 3. Empty State or Timeline Items
        if (uiState.filteredLogs.isEmpty()) {
            item(key = "empty_logs") {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = HugeIcons.Clock04,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Text(
                            text = if (uiState.allLogs.isEmpty()) {
                                "No activity recorded yet"
                            } else {
                                "No ${uiState.selectedLogFilter.label.lowercase()} events"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (uiState.allLogs.isEmpty()) {
                                "Incoming Page alerts, inline replies, Messenger call attempts, and observable UI states will appear here in chronological order."
                            } else {
                                "Switch to \"All\" to view other recorded audit events."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(
                items = uiState.filteredLogs,
                key = { entry -> if (entry.id != 0L) entry.id else "${entry.timestampMillis}_${entry.title}" }
            ) { entry ->
                AuditLogTimelineCard(entry = entry)
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditLogTimelineCard(
    entry: AuditLogEntry
) {
    var expanded by rememberSaveable(entry.id, entry.timestampMillis) { mutableStateOf(false) }
    val semantic = PageRelayThemeExtras.semantic

    val (icon, iconBg, iconTint) = when {
        entry.type == LogEventType.EMERGENCY_STOP -> Triple(
            HugeIcons.StopCircle,
            semantic.emergencyContainer,
            semantic.onEmergencyContainer
        )
        entry.isError || entry.type == LogEventType.ERROR -> Triple(
            HugeIcons.AlertCircle,
            semantic.emergencyContainer,
            semantic.onEmergencyContainer
        )
        entry.type == LogEventType.CALL_ATTEMPT -> Triple(
            HugeIcons.CallRinging02,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        entry.type == LogEventType.CAPTURE_CONVERSATION -> Triple(
            HugeIcons.UserSearch01,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        entry.type == LogEventType.STATIC_REPLY -> Triple(
            HugeIcons.MailReply01,
            semantic.successContainer,
            semantic.onSuccessContainer
        )
        entry.type == LogEventType.GEMINI_REPLY -> Triple(
            HugeIcons.Sparkles,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        entry.type == LogEventType.INCOMING_MESSAGE -> Triple(
            HugeIcons.Notification03,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        else -> Triple(
            HugeIcons.InformationCircle,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (entry.isError || entry.callStatus?.isFailure == true) {
                semantic.emergency.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.type.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = iconTint,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formatLogTimestamp(entry.timestampMillis),
                            style = MonoTimestampStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (entry.summary.isNotBlank()) {
                        Text(
                            text = entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Explicit observable call status + recipient + duration pills
            val hasMetadata = entry.callStatus != null ||
                !entry.recipientName.isNullOrBlank() ||
                entry.durationSeconds != null

            if (hasMetadata) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.callStatus?.let { status ->
                        val (bg, fg) = callStatusColors(status)
                        StatusPill(
                            text = status.label,
                            containerColor = bg,
                            contentColor = fg
                        )
                    }
                    if (!entry.recipientName.isNullOrBlank()) {
                        StatusPill(
                            text = "Recipient: ${entry.recipientName}",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    entry.durationSeconds?.let { dur ->
                        StatusPill(
                            text = formatDuration(dur),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = HugeIcons.Timer02
                        )
                    }
                }
            }

            // Expandable Diagnostic Details
            if (!entry.diagnosticDetails.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = !expanded }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Diagnostic Details",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                contentDescription = if (expanded) "Hide diagnostic details" else "Show diagnostic details",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = entry.diagnosticDetails,
                                style = MonoDiagnosticStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun callStatusColors(status: CallObservableStatus): Pair<Color, Color> {
    val semantic = PageRelayThemeExtras.semantic
    return when {
        status.isFailure -> semantic.emergencyContainer to semantic.onEmergencyContainer
        status.isVerifiedActive ||
            status == CallObservableStatus.DRY_RUN_VERIFIED ||
            status == CallObservableStatus.ENDED_AUTOMATICALLY ->
            semantic.successContainer to semantic.onSuccessContainer
        status == CallObservableStatus.CALL_INITIATED_UNVERIFIED ||
            status == CallObservableStatus.COMPLETED_HANDOFF ||
            status == CallObservableStatus.SKIPPED_DISABLED ||
            status == CallObservableStatus.SKIPPED_DUPLICATE ->
            semantic.warningContainer to semantic.onWarningContainer
        else ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
}
