package com.pagerelay.bot.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.pagerelay.bot.domain.ExecutionFlowSpec
import com.pagerelay.bot.domain.PipelineStepDescriptor
import com.pagerelay.bot.domain.PipelineStepState
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Tick02

@Composable
fun PipelineStepperCard(
    stepStates: List<PipelineStepState>,
    currentStepMessage: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val semantic = PageRelayThemeExtras.semantic

    val activeIndex = stepStates.indexOfFirst { it == PipelineStepState.ACTIVE }
    val failedIndex = stepStates.indexOfFirst { it == PipelineStepState.FAILED }
    val completedCount = stepStates.count { it == PipelineStepState.COMPLETED }

    SectionCard(
        title = "9-Step Execution Flow",
        subtitle = currentStepMessage,
        icon = HugeIcons.Flash,
        modifier = modifier.animateContentSize(),
        trailingHeader = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = when {
                            failedIndex >= 0 -> "Step ${failedIndex + 1} failed"
                            activeIndex >= 0 -> "Step ${activeIndex + 1}/9"
                            completedCount > 0 -> "$completedCount/9 done"
                            else -> if (expanded) "Hide" else "Show"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = if (expanded) "Collapse execution flow" else "Expand execution flow",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    ) {
        if (!expanded) {
            // Compact 9-dot progress overview when collapsed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExecutionFlowSpec.steps.forEachIndexed { index, _ ->
                    val state = stepStates.getOrElse(index) { PipelineStepState.IDLE }
                    val dotColor = when (state) {
                        PipelineStepState.COMPLETED -> semantic.success
                        PipelineStepState.ACTIVE -> MaterialTheme.colorScheme.primary
                        PipelineStepState.WARNING -> semantic.warning
                        PipelineStepState.FAILED -> semantic.emergency
                        PipelineStepState.IDLE -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(dotColor)
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                ExecutionFlowSpec.steps.forEachIndexed { index, descriptor ->
                    val state = stepStates.getOrElse(index) { PipelineStepState.IDLE }
                    PipelineStepRow(
                        descriptor = descriptor,
                        state = state,
                        isLast = index == ExecutionFlowSpec.steps.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineStepRow(
    descriptor: PipelineStepDescriptor,
    state: PipelineStepState,
    isLast: Boolean
) {
    val semantic = PageRelayThemeExtras.semantic
    val badgeBg: Color
    val badgeFg: Color
    val lineColor: Color

    when (state) {
        PipelineStepState.COMPLETED -> {
            badgeBg = semantic.success
            badgeFg = Color.White
            lineColor = semantic.success.copy(alpha = 0.55f)
        }
        PipelineStepState.ACTIVE -> {
            badgeBg = MaterialTheme.colorScheme.primary
            badgeFg = MaterialTheme.colorScheme.onPrimary
            lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        }
        PipelineStepState.WARNING -> {
            badgeBg = semantic.warning
            badgeFg = Color.White
            lineColor = semantic.warning.copy(alpha = 0.45f)
        }
        PipelineStepState.FAILED -> {
            badgeBg = semantic.emergency
            badgeFg = Color.White
            lineColor = semantic.emergency.copy(alpha = 0.45f)
        }
        PipelineStepState.IDLE -> {
            badgeBg = MaterialTheme.colorScheme.surfaceVariant
            badgeFg = MaterialTheme.colorScheme.onSurfaceVariant
            lineColor = MaterialTheme.colorScheme.outlineVariant
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(30.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    PipelineStepState.COMPLETED -> Icon(
                        imageVector = HugeIcons.Tick02,
                        contentDescription = null,
                        tint = badgeFg,
                        modifier = Modifier.size(15.dp)
                    )
                    PipelineStepState.FAILED -> Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                        tint = badgeFg,
                        modifier = Modifier.size(15.dp)
                    )
                    PipelineStepState.WARNING -> Icon(
                        imageVector = HugeIcons.Alert02,
                        contentDescription = null,
                        tint = badgeFg,
                        modifier = Modifier.size(14.dp)
                    )
                    else -> Text(
                        text = descriptor.number.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(26.dp)
                        .background(lineColor)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = descriptor.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (state == PipelineStepState.IDLE) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        badgeBg
                    }
                )
                if (state == PipelineStepState.ACTIVE) {
                    StatusPill(
                        text = "Running",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else if (state == PipelineStepState.WARNING) {
                    StatusPill(
                        text = "Check",
                        containerColor = semantic.warningContainer,
                        contentColor = semantic.onWarningContainer
                    )
                }
            }
            Text(
                text = descriptor.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
