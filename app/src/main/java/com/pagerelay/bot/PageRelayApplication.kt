package com.pagerelay.bot

import android.app.Application
import com.pagerelay.bot.ai.GeminiReplyClient
import com.pagerelay.bot.automation.AutomationCoordinator
import com.pagerelay.bot.data.AuditDatabase
import com.pagerelay.bot.data.AuditLogRepository
import com.pagerelay.bot.data.SettingsRepository
import com.pagerelay.bot.domain.AuditLogStore
import com.pagerelay.bot.domain.AutomationController
import com.pagerelay.bot.domain.PageRelayAppProvider
import com.pagerelay.bot.domain.PageRelayContainer
import com.pagerelay.bot.domain.SettingsStore

class PageRelayApplication : Application(), PageRelayAppProvider {

    private lateinit var _settingsRepository: SettingsRepository
    private lateinit var _auditLogRepository: AuditLogRepository
    private lateinit var _geminiClient: GeminiReplyClient
    private lateinit var _automationCoordinator: AutomationCoordinator

    val automationCoordinator: AutomationCoordinator
        get() = _automationCoordinator

    override lateinit var container: PageRelayContainer
        private set

    override fun onCreate() {
        super.onCreate()
        _settingsRepository = SettingsRepository(applicationContext)
        val database = AuditDatabase.getInstance(applicationContext)
        _auditLogRepository = AuditLogRepository(database.auditLogDao())
        _geminiClient = GeminiReplyClient()
        _automationCoordinator = AutomationCoordinator(
            context = applicationContext,
            settingsStore = _settingsRepository,
            auditLogStore = _auditLogRepository,
            geminiClient = _geminiClient
        )

        container = object : PageRelayContainer {
            override val settingsStore: SettingsStore = _settingsRepository
            override val auditLogStore: AuditLogStore = _auditLogRepository
            override val automationController: AutomationController = _automationCoordinator
        }
    }
}
