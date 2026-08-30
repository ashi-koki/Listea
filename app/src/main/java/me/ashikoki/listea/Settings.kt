package me.ashikoki.listea

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity

/**
 * App-wide configuration: the settings that outlive any one list, folder or review.
 *
 * Deliberately not in Room. These belong to the app, not to a list, and keeping them out of the
 * database is what lets an action be renamed without touching a single item row.
 *
 * The active browsing root stays in [FolderStore]: it is a SAF grant, it already persists
 * reliably, and moving it here would buy nothing.
 */
data class AppSettings(
    val folderQuickReviewEnabled: Boolean = true,

    val custom1DisplayName: String = ItemAction.CUSTOM1.defaultLabel,
    val custom1WebhookValue: String = ItemAction.CUSTOM1.defaultWireName,

    val custom2DisplayName: String = ItemAction.CUSTOM2.defaultLabel,
    val custom2WebhookValue: String = ItemAction.CUSTOM2.defaultWireName,

    val defaultWebhookEnabled: Boolean = false,
    val defaultWebhookUrl: String = "",

    val videoAutoplay: Boolean = true,
    val videoStartMuted: Boolean = false,

    /**
     * Whether full-list Review resumes where it left off. True is the long-standing behaviour and
     * stays the default; Quick Review is folder-scoped and temporary, so it always starts at the
     * first unchecked item regardless.
     */
    val rememberReviewPosition: Boolean = true
) {
    /**
     * What the user sees this action called. Favourite is not configurable in V3.8, so it answers
     * with its built-in label.
     */
    fun labelOf(action: ItemAction): String = when (action) {
        ItemAction.FAVORITE -> action.defaultLabel
        ItemAction.CUSTOM1 -> custom1DisplayName
        ItemAction.CUSTOM2 -> custom2DisplayName
    }

    /**
     * What the webhook calls this action. Resolved from settings at delivery time, never stored
     * on the item: an item's boolean means "custom slot 1 is selected", not "the string cust1 was
     * selected", so renaming the wire value changes future payloads without migrating any row.
     */
    fun wireOf(action: ItemAction): String = when (action) {
        ItemAction.FAVORITE -> action.defaultWireName
        ItemAction.CUSTOM1 -> custom1WebhookValue
        ItemAction.CUSTOM2 -> custom2WebhookValue
    }
}

/**
 * The enabled actions of [item] as wire values, in [ItemAction] order.
 *
 * Duplicates are collapsed rather than rejected: two slots configured with the same wire value is
 * a strange thing to do but not a dangerous one, and emitting `["tag","tag"]` would be worse for
 * a receiver than emitting it once. Order stays fixed either way.
 */
fun itemActionNames(item: ListItemEntity, settings: AppSettings): List<String> =
    ItemAction.entries.filter { it.isSetOn(item) }.map { settings.wireOf(it) }.distinct()

/** Compact "★ · Save" summary for a checklist row, or null when the item carries no actions. */
fun itemActionLabel(item: ListItemEntity, settings: AppSettings): String? =
    ItemAction.entries.filter { it.isSetOn(item) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ") { settings.labelOf(it) }

/** Rejects only what would actually break something: an empty or whitespace-only value. */
fun settingsTextError(value: String): String? =
    if (value.isBlank()) "Cannot be empty" else null

private val Context.settingsDataStore by preferencesDataStore(name = "listea_settings")

/**
 * Reads and writes [AppSettings]. Small on purpose: no repository layer, no domain mapping, just
 * a flow for the UI and an authoritative one-shot read for the moments where a stale value would
 * be wrong (creating a list, sending a webhook).
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        // A corrupt or unreadable file falls back to defaults rather than taking the app down.
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            val defaults = AppSettings()
            AppSettings(
                folderQuickReviewEnabled = prefs[KEY_QUICK_REVIEW]
                    ?: defaults.folderQuickReviewEnabled,
                custom1DisplayName = prefs[KEY_C1_NAME] ?: defaults.custom1DisplayName,
                custom1WebhookValue = prefs[KEY_C1_VALUE] ?: defaults.custom1WebhookValue,
                custom2DisplayName = prefs[KEY_C2_NAME] ?: defaults.custom2DisplayName,
                custom2WebhookValue = prefs[KEY_C2_VALUE] ?: defaults.custom2WebhookValue,
                defaultWebhookEnabled = prefs[KEY_WEBHOOK_ENABLED]
                    ?: defaults.defaultWebhookEnabled,
                defaultWebhookUrl = prefs[KEY_WEBHOOK_URL] ?: defaults.defaultWebhookUrl,
                videoAutoplay = prefs[KEY_VIDEO_AUTOPLAY] ?: defaults.videoAutoplay,
                videoStartMuted = prefs[KEY_VIDEO_MUTED] ?: defaults.videoStartMuted,
                rememberReviewPosition = prefs[KEY_REMEMBER_POSITION]
                    ?: defaults.rememberReviewPosition
            )
        }

    suspend fun read(): AppSettings = settings.first()

    suspend fun setFolderQuickReviewEnabled(enabled: Boolean) =
        put { it[KEY_QUICK_REVIEW] = enabled }

    suspend fun setCustom1DisplayName(name: String) = put { it[KEY_C1_NAME] = name.trim() }

    suspend fun setCustom1WebhookValue(value: String) = put { it[KEY_C1_VALUE] = value.trim() }

    suspend fun setCustom2DisplayName(name: String) = put { it[KEY_C2_NAME] = name.trim() }

    suspend fun setCustom2WebhookValue(value: String) = put { it[KEY_C2_VALUE] = value.trim() }

    suspend fun setDefaultWebhookEnabled(enabled: Boolean) =
        put { it[KEY_WEBHOOK_ENABLED] = enabled }

    suspend fun setDefaultWebhookUrl(url: String) = put { it[KEY_WEBHOOK_URL] = url.trim() }

    suspend fun setVideoAutoplay(enabled: Boolean) = put { it[KEY_VIDEO_AUTOPLAY] = enabled }

    suspend fun setVideoStartMuted(muted: Boolean) = put { it[KEY_VIDEO_MUTED] = muted }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_QUICK_REVIEW = booleanPreferencesKey("folder_quick_review_enabled")
        val KEY_C1_NAME = stringPreferencesKey("custom1_display_name")
        val KEY_C1_VALUE = stringPreferencesKey("custom1_webhook_value")
        val KEY_C2_NAME = stringPreferencesKey("custom2_display_name")
        val KEY_C2_VALUE = stringPreferencesKey("custom2_webhook_value")
        val KEY_WEBHOOK_ENABLED = booleanPreferencesKey("default_webhook_enabled")
        val KEY_WEBHOOK_URL = stringPreferencesKey("default_webhook_url")
        val KEY_VIDEO_AUTOPLAY = booleanPreferencesKey("video_autoplay")
        val KEY_VIDEO_MUTED = booleanPreferencesKey("video_start_muted")
        val KEY_REMEMBER_POSITION = booleanPreferencesKey("remember_review_position")
    }
}
