package com.pagerelay.bot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagerelay.bot.ui.MainUiState
import com.pagerelay.bot.ui.MainViewModel
import com.pagerelay.bot.ui.SystemSettingsNavigator
import com.pagerelay.bot.ui.components.EmergencyStopBar
import com.pagerelay.bot.ui.screens.ActivityLogsScreen
import com.pagerelay.bot.ui.screens.AutoCallScreen
import com.pagerelay.bot.ui.screens.DashboardScreen
import com.pagerelay.bot.ui.screens.RepliesAiScreen
import com.pagerelay.bot.ui.theme.CrimsonStop
import com.pagerelay.bot.ui.theme.PageRelayTheme
import com.pagerelay.bot.ui.theme.PageRelayThemeExtras
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallRinging02
import me.rerere.hugeicons.stroke.Clock04
import me.rerere.hugeicons.stroke.DashboardSquare02
import me.rerere.hugeicons.stroke.MessageMultiple02
import me.rerere.hugeicons.stroke.StopCircle

private enum class PageRelayTab(
    val label: String,
    val icon: ImageVector
) {
    OVERVIEW("Overview", HugeIcons.DashboardSquare02),
    AUTO_CALL("Auto-Call", HugeIcons.CallRinging02),
    REPLIES_AI("Replies & AI", HugeIcons.MessageMultiple02),
    ACTIVITY("Activity", HugeIcons.Clock04)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.showSnackbar(
                "Notifications denied — status & quick Emergency Stop notification will be hidden."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPostNotificationsIfNeeded()

        setContent {
            PageRelayTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                PageRelayAppScaffold(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironmentStatus()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val status = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (status != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageRelayAppScaffold(
    uiState: MainUiState,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(PageRelayTab.OVERVIEW) }
    val semantic = PageRelayThemeExtras.semantic

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSnackbar()
        }
    }

    if (uiState.showEmergencyConfirmDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEmergencyStopDialog,
            title = { Text("Activate Emergency Stop?") },
            text = {
                Text(
                    "This immediately halts notification monitoring, cancels any queued or active " +
                        "Messenger call automation, and locks automation off until you manually re-arm."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::triggerEmergencyStop) {
                    Text(
                        text = "STOP NOW",
                        color = CrimsonStop,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEmergencyStopDialog) {
                    Text("Cancel")
                }
            }
        )
    }

    val statusDotColor = when {
        uiState.settings.emergencyStopped -> semantic.emergency
        uiState.liveState.isCallInProgress -> MaterialTheme.colorScheme.primary
        uiState.settings.automationEnabled -> semantic.success
        else -> MaterialTheme.colorScheme.outline
    }

    val openMessengerAction: () -> Unit = {
        if (!SystemSettingsNavigator.openFacebookMessenger(context)) {
            viewModel.showSnackbar("Official Facebook Messenger (com.facebook.orca) is not installed.")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Text(
                            text = "PageRelay",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    val isArmedOrCalling = (uiState.settings.automationEnabled && !uiState.settings.emergencyStopped) ||
                        uiState.liveState.isCallInProgress
                    if (isArmedOrCalling) {
                        IconButton(
                            onClick = {
                                if (uiState.liveState.isCallInProgress) {
                                    viewModel.triggerEmergencyStop()
                                } else {
                                    viewModel.requestEmergencyStopDialog()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.StopCircle,
                                contentDescription = "Emergency Stop",
                                tint = CrimsonStop
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                PageRelayTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            EmergencyStopBar(
                isEmergencyStopped = uiState.settings.emergencyStopped,
                isAutomationArmed = uiState.settings.automationEnabled && !uiState.settings.emergencyStopped,
                isCallInProgress = uiState.liveState.isCallInProgress,
                currentCallStatus = uiState.liveState.currentCallStatus,
                elapsedSeconds = uiState.liveState.currentCallElapsedSeconds,
                onEmergencyStop = viewModel::triggerEmergencyStop,
                onRearm = { viewModel.rearmAfterEmergencyStop(enableAutomation = true) }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    PageRelayTab.OVERVIEW -> DashboardScreen(
                        uiState = uiState,
                        onToggleAutomation = viewModel::setAutomationEnabled,
                        onEmergencyStop = viewModel::triggerEmergencyStop,
                        onRearm = viewModel::rearmAfterEmergencyStop,
                        onOpenNotificationSettings = {
                            if (!SystemSettingsNavigator.openNotificationListenerSettings(context)) {
                                viewModel.showSnackbar("Could not open Notification Access settings.")
                            }
                        },
                        onOpenAccessibilitySettings = {
                            if (!SystemSettingsNavigator.openAccessibilitySettings(context)) {
                                viewModel.showSnackbar("Could not open Accessibility settings.")
                            }
                        },
                        onOpenMessenger = openMessengerAction,
                        onCancelCapture = viewModel::cancelRecipientCapture,
                        onNavigateToReplies = { selectedTab = PageRelayTab.REPLIES_AI },
                        onNavigateToAutoCall = { selectedTab = PageRelayTab.AUTO_CALL },
                        onRunDryRun = { viewModel.runTestCall(dryRunOnly = true) },
                        onRunLiveTestCall = { viewModel.runTestCall(dryRunOnly = false) }
                    )

                    PageRelayTab.AUTO_CALL -> AutoCallScreen(
                        uiState = uiState,
                        onSetAutoCallEnabled = viewModel::setAutoCallEnabled,
                        onUpdateSecondaryAccountName = viewModel::updateSecondaryAccountName,
                        onStartRecipientCapture = viewModel::startRecipientCapture,
                        onCancelRecipientCapture = viewModel::cancelRecipientCapture,
                        onClearSavedRecipient = viewModel::clearSavedRecipient,
                        onOpenMessenger = openMessengerAction,
                        onUpdateMaxCallDuration = viewModel::updateMaxCallDuration,
                        onSetAutoEndCallEnabled = viewModel::setAutoEndCallEnabled,
                        onUpdateDuplicateCooldown = viewModel::updateDuplicateCooldown,
                        onUpdateEmulatorProfile = viewModel::updateEmulatorProfile,
                        onRunDryRun = { viewModel.runTestCall(dryRunOnly = true) },
                        onRunLiveTestCall = { viewModel.runTestCall(dryRunOnly = false) },
                        onOpenAccessibilitySettings = {
                            if (!SystemSettingsNavigator.openAccessibilitySettings(context)) {
                                viewModel.showSnackbar("Could not open Accessibility settings.")
                            }
                        }
                    )

                    PageRelayTab.REPLIES_AI -> RepliesAiScreen(
                        uiState = uiState,
                        onUpdateTargetPageName = viewModel::updateTargetPageName,
                        onUpdateStaticReplyMessage = viewModel::updateStaticReplyMessage,
                        onSetGeminiEnabled = viewModel::setGeminiEnabled,
                        onUpdateGeminiApiKey = viewModel::updateGeminiApiKey,
                        onUpdateGeminiModel = viewModel::updateGeminiModel,
                        onUpdateGeminiSystemPrompt = viewModel::updateGeminiSystemPrompt,
                        onTestGeminiConnection = viewModel::testGeminiConnection,
                        onResetConversationSession = viewModel::resetConversationSession,
                        onClearAllSessions = viewModel::clearConversationSessions
                    )

                    PageRelayTab.ACTIVITY -> ActivityLogsScreen(
                        uiState = uiState,
                        onSelectFilter = viewModel::setLogFilter,
                        onCopyLogs = {
                            viewModel.exportLogs { summary ->
                                val copied = SystemSettingsNavigator.copyToClipboard(
                                    context = context,
                                    label = "PageRelay Activity Logs",
                                    text = summary
                                )
                                viewModel.showSnackbar(
                                    if (copied) "Activity log summary copied to clipboard."
                                    else "Unable to copy logs to clipboard."
                                )
                            }
                        },
                        onShareLogs = {
                            viewModel.exportLogs { summary ->
                                if (!SystemSettingsNavigator.sharePlainText(
                                        context = context,
                                        title = "PageRelay Activity Logs",
                                        text = summary
                                    )
                                ) {
                                    viewModel.showSnackbar("No app available to share logs.")
                                }
                            }
                        },
                        onClearAllLogs = viewModel::clearAllLogs
                    )
                }
            }
        }
    }
}
