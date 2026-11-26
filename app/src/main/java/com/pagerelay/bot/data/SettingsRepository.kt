package com.pagerelay.bot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pagerelay.bot.domain.AppSettings
import com.pagerelay.bot.domain.EmulatorProfile
import com.pagerelay.bot.domain.SavedRecipientConversation
import com.pagerelay.bot.domain.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.pageRelaySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pagerelay_settings"
)

class SettingsRepository(
    private val context: Context
) : SettingsStore {

    private object Keys {
        val AUTOMATION_ENABLED = booleanPreferencesKey("automation_enabled")
        val EMERGENCY_STOPPED = booleanPreferencesKey("emergency_stopped")
        val TARGET_PAGE_NAME = stringPreferencesKey("target_page_name")
        val STATIC_REPLY_MESSAGE = stringPreferencesKey("static_reply_message")
        val GEMINI_ENABLED = booleanPreferencesKey("gemini_enabled")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GEMINI_SYSTEM_PROMPT = stringPreferencesKey("gemini_system_prompt")
        val AUTO_CALL_ENABLED = booleanPreferencesKey("auto_call_enabled")
        val SECONDARY_ACCOUNT_NAME = stringPreferencesKey("secondary_account_name")
        val SAVED_RECIPIENT_DISPLAY_NAME = stringPreferencesKey("saved_recipient_display_name")
        val SAVED_RECIPIENT_SECONDARY_LABEL = stringPreferencesKey("saved_recipient_secondary_label")
        val SAVED_RECIPIENT_SIGNATURE = stringPreferencesKey("saved_recipient_signature")
        val SAVED_RECIPIENT_CAPTURED_AT = longPreferencesKey("saved_recipient_captured_at")
        val MAX_CALL_DURATION_SECONDS = intPreferencesKey("max_call_duration_seconds")
        val AUTO_END_CALL_ENABLED = booleanPreferencesKey("auto_end_call_enabled")
        val DUPLICATE_CALL_COOLDOWN_SECONDS = intPreferencesKey("duplicate_call_cooldown_seconds")
        val EMULATOR_PROFILE = stringPreferencesKey("emulator_profile")
    }

    private val defaults = AppSettings()

    override val settings: Flow<AppSettings> = context.pageRelaySettingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> mapPreferencesToSettings(prefs) }

    override suspend fun getCurrentSettings(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.pageRelaySettingsDataStore.edit { prefs ->
            val current = mapPreferencesToSettings(prefs)
            val updated = sanitize(transform(current))
            prefs[Keys.AUTOMATION_ENABLED] = updated.automationEnabled
            prefs[Keys.EMERGENCY_STOPPED] = updated.emergencyStopped
            prefs[Keys.TARGET_PAGE_NAME] = updated.targetPageName
            prefs[Keys.STATIC_REPLY_MESSAGE] = updated.staticReplyMessage
            prefs[Keys.GEMINI_ENABLED] = updated.geminiEnabled
            prefs[Keys.GEMINI_API_KEY] = updated.geminiApiKey
            prefs[Keys.GEMINI_MODEL] = updated.geminiModel
            prefs[Keys.GEMINI_SYSTEM_PROMPT] = updated.geminiSystemPrompt
            prefs[Keys.AUTO_CALL_ENABLED] = updated.autoCallEnabled
            prefs[Keys.SECONDARY_ACCOUNT_NAME] = updated.secondaryAccountName
            prefs[Keys.SAVED_RECIPIENT_DISPLAY_NAME] = updated.savedRecipient.displayName
            prefs[Keys.SAVED_RECIPIENT_SECONDARY_LABEL] = updated.savedRecipient.secondaryLabel
            prefs[Keys.SAVED_RECIPIENT_SIGNATURE] = updated.savedRecipient.conversationSignature
            prefs[Keys.SAVED_RECIPIENT_CAPTURED_AT] = updated.savedRecipient.capturedAtMillis
            prefs[Keys.MAX_CALL_DURATION_SECONDS] = updated.maxCallDurationSeconds
            prefs[Keys.AUTO_END_CALL_ENABLED] = updated.autoEndCallEnabled
            prefs[Keys.DUPLICATE_CALL_COOLDOWN_SECONDS] = updated.duplicateCallCooldownSeconds
            prefs[Keys.EMULATOR_PROFILE] = updated.emulatorProfile.name
        }
    }

    private fun mapPreferencesToSettings(prefs: Preferences): AppSettings {
        val savedRecipient = SavedRecipientConversation(
            displayName = prefs[Keys.SAVED_RECIPIENT_DISPLAY_NAME] ?: defaults.savedRecipient.displayName,
            secondaryLabel = prefs[Keys.SAVED_RECIPIENT_SECONDARY_LABEL] ?: defaults.savedRecipient.secondaryLabel,
            conversationSignature = prefs[Keys.SAVED_RECIPIENT_SIGNATURE] ?: defaults.savedRecipient.conversationSignature,
            capturedAtMillis = prefs[Keys.SAVED_RECIPIENT_CAPTURED_AT] ?: defaults.savedRecipient.capturedAtMillis
        )
        return AppSettings(
            automationEnabled = prefs[Keys.AUTOMATION_ENABLED] ?: defaults.automationEnabled,
            emergencyStopped = prefs[Keys.EMERGENCY_STOPPED] ?: defaults.emergencyStopped,
            targetPageName = prefs[Keys.TARGET_PAGE_NAME] ?: defaults.targetPageName,
            staticReplyMessage = prefs[Keys.STATIC_REPLY_MESSAGE] ?: defaults.staticReplyMessage,
            geminiEnabled = prefs[Keys.GEMINI_ENABLED] ?: defaults.geminiEnabled,
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: defaults.geminiApiKey,
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: defaults.geminiModel,
            geminiSystemPrompt = prefs[Keys.GEMINI_SYSTEM_PROMPT] ?: defaults.geminiSystemPrompt,
            autoCallEnabled = prefs[Keys.AUTO_CALL_ENABLED] ?: defaults.autoCallEnabled,
            secondaryAccountName = prefs[Keys.SECONDARY_ACCOUNT_NAME] ?: defaults.secondaryAccountName,
            savedRecipient = savedRecipient,
            maxCallDurationSeconds = (prefs[Keys.MAX_CALL_DURATION_SECONDS] ?: defaults.maxCallDurationSeconds)
                .coerceIn(MIN_CALL_DURATION_SECONDS, MAX_CALL_DURATION_SECONDS),
            autoEndCallEnabled = prefs[Keys.AUTO_END_CALL_ENABLED] ?: defaults.autoEndCallEnabled,
            duplicateCallCooldownSeconds = (prefs[Keys.DUPLICATE_CALL_COOLDOWN_SECONDS]
                ?: defaults.duplicateCallCooldownSeconds)
                .coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS),
            emulatorProfile = EmulatorProfile.fromName(prefs[Keys.EMULATOR_PROFILE])
        )
    }

    private fun sanitize(settings: AppSettings): AppSettings = settings.copy(
        geminiModel = settings.geminiModel.trim().ifBlank { AppSettings.DEFAULT_GEMINI_MODEL },
        maxCallDurationSeconds = settings.maxCallDurationSeconds.coerceIn(
            MIN_CALL_DURATION_SECONDS,
            MAX_CALL_DURATION_SECONDS
        ),
        duplicateCallCooldownSeconds = settings.duplicateCallCooldownSeconds.coerceIn(
            MIN_COOLDOWN_SECONDS,
            MAX_COOLDOWN_SECONDS
        )
    )

    companion object {
        const val MIN_CALL_DURATION_SECONDS = 10
        const val MAX_CALL_DURATION_SECONDS = 600
        const val MIN_COOLDOWN_SECONDS = 5
        const val MAX_COOLDOWN_SECONDS = 3600
    }
}
