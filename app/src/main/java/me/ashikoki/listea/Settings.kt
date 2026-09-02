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
import me.ashikoki.listea.data.ReviewDecisions
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
     *
     * This decides what is *read* on entry, never what is written: the position keeps being
     * recorded either way, so switching back on resumes from where Review actually got to rather
     * than from wherever it was when the setting was switched off.
     */
    val rememberReviewPosition: Boolean = true,

    /**
     * Whether opening a folder-backed list scans its source folder to see if it has changed.
     *
     * On is the long-standing behaviour. Off suppresses only the *automatic* scan: "Update from
     * folder" still works, and so does the Quick Review gate, because both are things the user
     * asked for. With it off a list reports [SourceFreshness.NotChecked] rather than an
     * up-to-date verdict nothing has established.
     */
    val autoCheckSourceFreshness: Boolean = true,

    /**
     * Whether finishing a Quick Review queue delivers a webhook of its own.
     *
     * Quick Review has no webhook configuration of its own and is not going to get one: it is
     * folder-scoped and temporary, so it posts to [defaultWebhookUrl] as it stands right now.
     * This switch is the whole gate — [defaultWebhookEnabled] is only a template for new lists
     * and has no say here — and an empty default URL is reported exactly like any other unusable
     * one rather than passing silently.
     *
     * Off by default, which is the behaviour Quick Review has always had: it edits the owning
     * list's real items, so a list that *completes* during a Quick Review still fires that list's
     * own `list.completed` webhook either way.
     */
    val quickReviewWebhookEnabled: Boolean = false,

    /**
     * Whether a payload carries only the items that are checked.
     *
     * Global: it applies to every event, the test webhook included, so what the test button sends
     * is still what a real delivery would send. Off is the long-standing behaviour, where a
     * receiver sees every item and decides for itself using `isCompleted`.
     *
     * Nothing is filtered out of a *review*, only out of what is sent. Pair it with
     * [reviewUncheckedOnly] to deliver just the decisions of one round.
     */
    val webhookCompletedItemsOnly: Boolean = false,

    /**
     * Whether entering a review queues only the items that are not checked yet.
     *
     * The queue is fixed when the review opens, not re-evaluated as items are checked: an item
     * checked mid-review stays in front of the user and stays swipeable backwards. Leaving and
     * re-entering is what starts a new round.
     *
     * Off is the long-standing behaviour, where a review walks everything it is given.
     */
    val reviewUncheckedOnly: Boolean = false,

    /**
     * Whether one filter and one sort order are shared by every folder and every list, or each
     * of them keeps its own.
     *
     * On by default, which is the answer that matches what setting a filter usually means: a user
     * who asks for "videos, newest first" is describing how they want to browse, not how they
     * want to browse *this* folder. Off is the richer behaviour and costs a per-folder memory,
     * which is why it is the one you opt into.
     *
     * Switching it never discards anything. Both the shared arrangement and the per-listing ones
     * are kept whichever way it is set, so turning it on to sweep through a root and turning it
     * back off returns every folder and list to what it had — see [FolderArrangements].
     */
    val sharedFileArrangement: Boolean = true,

    /**
     * Whether leaving a review delivers a webhook for the queue that was just reviewed.
     *
     * Full Review uses the list's own webhook configuration, exactly as its completion does;
     * Quick Review uses [defaultWebhookUrl] and is additionally gated on
     * [quickReviewWebhookEnabled], because that switch is what makes Quick Review send anything
     * at all. Either way the payload covers the review's own queue, not the whole list.
     *
     * A queue that finished on screen has already sent, and is not sent again on the way out.
     */
    val webhookOnReviewExit: Boolean = false
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
 * The enabled actions in [decisions] as wire values, in [ItemAction] order.
 *
 * Duplicates are collapsed rather than rejected: two slots configured with the same wire value is
 * a strange thing to do but not a dangerous one, and emitting `["tag","tag"]` would be worse for
 * a receiver than emitting it once. Order stays fixed either way.
 */
fun itemActionNames(decisions: ReviewDecisions, settings: AppSettings): List<String> =
    ItemAction.entries.filter { it.isSetOn(decisions) }.map { settings.wireOf(it) }.distinct()

/** Compact "★ · Save" summary for a checklist row, or null when the item carries no actions. */
fun itemActionLabel(decisions: ReviewDecisions, settings: AppSettings): String? =
    ItemAction.entries.filter { it.isSetOn(decisions) }
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
                    ?: defaults.rememberReviewPosition,
                autoCheckSourceFreshness = prefs[KEY_AUTO_CHECK_FRESHNESS]
                    ?: defaults.autoCheckSourceFreshness,
                quickReviewWebhookEnabled = prefs[KEY_QUICK_REVIEW_WEBHOOK]
                    ?: defaults.quickReviewWebhookEnabled,
                webhookCompletedItemsOnly = prefs[KEY_COMPLETED_ITEMS_ONLY]
                    ?: defaults.webhookCompletedItemsOnly,
                reviewUncheckedOnly = prefs[KEY_REVIEW_UNCHECKED_ONLY]
                    ?: defaults.reviewUncheckedOnly,
                webhookOnReviewExit = prefs[KEY_WEBHOOK_ON_REVIEW_EXIT]
                    ?: defaults.webhookOnReviewExit,
                sharedFileArrangement = prefs[KEY_SHARED_ARRANGEMENT]
                    ?: defaults.sharedFileArrangement
            )
        }

    /**
     * The remembered filters and sort orders, as their own flow rather than as fields on
     * [AppSettings].
     *
     * Kept apart because they are a different kind of thing on a different scale: [settings] is a
     * handful of switches read by most of the app, and this is a map that grows with the folders
     * a user has arranged and is read by one screen. Folding them together would re-parse every
     * remembered folder each time a webhook switch changed.
     */
    val arrangements: Flow<FolderArrangements> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs ->
            FolderArrangements(
                global = prefs[KEY_GLOBAL_ARRANGEMENT]?.let(::decodeArrangement)
                    ?: FileArrangement.Default,
                byFolder = prefs[KEY_FOLDER_ARRANGEMENTS]?.let(::decodeFolderArrangements)
                    ?: emptyMap()
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

    suspend fun setRememberReviewPosition(remember: Boolean) =
        put { it[KEY_REMEMBER_POSITION] = remember }

    suspend fun setAutoCheckSourceFreshness(enabled: Boolean) =
        put { it[KEY_AUTO_CHECK_FRESHNESS] = enabled }

    suspend fun setQuickReviewWebhookEnabled(enabled: Boolean) =
        put { it[KEY_QUICK_REVIEW_WEBHOOK] = enabled }

    suspend fun setWebhookCompletedItemsOnly(enabled: Boolean) =
        put { it[KEY_COMPLETED_ITEMS_ONLY] = enabled }

    suspend fun setReviewUncheckedOnly(enabled: Boolean) =
        put { it[KEY_REVIEW_UNCHECKED_ONLY] = enabled }

    suspend fun setWebhookOnReviewExit(enabled: Boolean) =
        put { it[KEY_WEBHOOK_ON_REVIEW_EXIT] = enabled }

    suspend fun setSharedFileArrangement(shared: Boolean) =
        put { it[KEY_SHARED_ARRANGEMENT] = shared }

    /** The one arrangement every folder uses while [AppSettings.sharedFileArrangement] is on. */
    suspend fun setGlobalArrangement(arrangement: FileArrangement) =
        put { it[KEY_GLOBAL_ARRANGEMENT] = encodeArrangement(arrangement) }

    /**
     * One folder's own arrangement, read-modify-write so the rest of the map is untouched.
     *
     * The whole map is rewritten because it is stored as one value; [rememberArrangement] is what
     * decides what that value becomes, including forgetting a folder that has been put back to
     * its default and dropping the least recently arranged folder once the cap is reached.
     */
    suspend fun setFolderArrangement(folderKey: String, arrangement: FileArrangement) = put { prefs ->
        val existing = prefs[KEY_FOLDER_ARRANGEMENTS]?.let(::decodeFolderArrangements).orEmpty()
        prefs[KEY_FOLDER_ARRANGEMENTS] =
            encodeFolderArrangements(rememberArrangement(existing, folderKey, arrangement))
    }

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
        val KEY_AUTO_CHECK_FRESHNESS = booleanPreferencesKey("auto_check_source_freshness")
        val KEY_QUICK_REVIEW_WEBHOOK = booleanPreferencesKey("quick_review_webhook_enabled")
        val KEY_COMPLETED_ITEMS_ONLY = booleanPreferencesKey("webhook_completed_items_only")
        val KEY_REVIEW_UNCHECKED_ONLY = booleanPreferencesKey("review_unchecked_only")
        val KEY_WEBHOOK_ON_REVIEW_EXIT = booleanPreferencesKey("webhook_on_review_exit")
        val KEY_SHARED_ARRANGEMENT = booleanPreferencesKey("shared_file_arrangement")
        val KEY_GLOBAL_ARRANGEMENT = stringPreferencesKey("global_file_arrangement")
        val KEY_FOLDER_ARRANGEMENTS = stringPreferencesKey("folder_file_arrangements")
    }
}
