package me.ashikoki.listea

import android.content.Context
import androidx.compose.runtime.Immutable
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
import me.ashikoki.listea.data.newActionId

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

    /**
     * The actions the review bar offers, beyond the built-in favourite, in the order it offers
     * them and the order the webhook emits them.
     *
     * A list rather than a fixed pair: there can be none, there can be many, and adding one is
     * not a schema change because an item stores the ids it carries rather than a column each.
     * Two are configured out of the box, which is what every existing install already had.
     */
    val customActions: List<CustomAction> = DefaultCustomActions,

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
        ItemAction.Favourite -> ItemAction.Favourite.Label
        is ItemAction.Custom -> customActionOf(action.id)?.displayName ?: action.id
    }

    /**
     * Every action the bar offers, favourite first and the configured ones in their own order.
     *
     * The one place that order is decided, so the bar, the Info sheet and the payload cannot
     * disagree about it.
     */
    val actions: List<ItemAction>
        get() = listOf(ItemAction.Favourite) + customActions.map { ItemAction.Custom(it.id) }

    /** The configuration behind an id, or null once the user has deleted it. */
    fun customActionOf(id: String): CustomAction? = customActions.firstOrNull { it.id == id }

    /**
     * What the webhook calls this action. Resolved from settings at delivery time, never stored
     * on the item: an item's boolean means "custom slot 1 is selected", not "the string cust1 was
     * selected", so renaming the wire value changes future payloads without migrating any row.
     */
    fun wireOf(action: ItemAction): String = when (action) {
        ItemAction.Favourite -> ItemAction.Favourite.WireName
        is ItemAction.Custom -> customActionOf(action.id)?.webhookValue ?: action.id
    }
}

/**
 * One configured action: what it is called, what it is sent as, and what items store to say they
 * carry it.
 *
 * [id] is the only part an item ever holds. [displayName] and [webhookValue] are free to change
 * under it — that separation is the whole reason renaming an action does not touch a single row —
 * and neither is required to be unique, because two actions that look alike or send alike are odd
 * rather than dangerous.
 */
@Immutable
data class CustomAction(
    val id: String,
    val displayName: String,
    val webhookValue: String
)

/**
 * What a fresh install starts with, and what the two original slots migrate to.
 *
 * The ids are the migration: an item marked with the old `custom1` column is written out as the
 * id `custom1`, so it keeps pointing at the action the user marked it with rather than becoming
 * an orphan on upgrade.
 */
val DefaultCustomActions: List<CustomAction> = listOf(
    CustomAction(id = "custom1", displayName = "C1", webhookValue = "cust1"),
    CustomAction(id = "custom2", displayName = "C2", webhookValue = "cust2")
)

/**
 * How the action list is stored: one record per line, three tab-separated fields.
 *
 * Hand-rolled rather than JSON because org.json is Android's and this has to be readable by a
 * host test. The two separators are stripped from anything the user types on the way in, so a
 * display name can be anything else at all without being able to corrupt the record after it.
 */
fun encodeCustomActions(actions: List<CustomAction>): String =
    actions.joinToString("\n") { "${it.id}\t${it.displayName}\t${it.webhookValue}" }

/**
 * Tolerant on purpose: a record with no id is dropped, a record missing later fields keeps what
 * it has, and an id carrying a comma is refused because that is the separator items store with.
 * Anything unreadable costs its own line rather than the whole list.
 */
fun decodeCustomActions(text: String): List<CustomAction> =
    text.split('\n').mapNotNull { line ->
        val fields = line.split('\t')
        val id = fields.getOrNull(0)?.trim().orEmpty()
        if (id.isEmpty() || id.contains(',')) return@mapNotNull null
        CustomAction(
            id = id,
            displayName = fields.getOrNull(1).orEmpty(),
            webhookValue = fields.getOrNull(2).orEmpty()
        )
    }

/** Keeps typed text to one line, so it cannot break the record it is stored in. */
fun sanitizeActionText(value: String): String =
    value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

/**
 * The name and wire value a newly added action starts with.
 *
 * Numbered from the first free slot rather than from the count, so adding one after deleting one
 * does not hand out a name that is already on the bar.
 */
fun nextCustomActionDefaults(existing: List<CustomAction>): CustomAction {
    val taken = existing.map { it.displayName }.toSet()
    var index = existing.size + 1
    while ("C$index" in taken) index++
    return CustomAction(id = newActionId(), displayName = "C$index", webhookValue = "cust$index")
}

/**
 * The enabled actions in [decisions] as wire values, in [ItemAction] order.
 *
 * Duplicates are collapsed rather than rejected: two slots configured with the same wire value is
 * a strange thing to do but not a dangerous one, and emitting `["tag","tag"]` would be worse for
 * a receiver than emitting it once. Order stays fixed either way.
 */
fun itemActionNames(decisions: ReviewDecisions, settings: AppSettings): List<String> =
    settings.actions.filter { it.isSetOn(decisions) }.map { settings.wireOf(it) }.distinct()

/** Compact "★ · Save" summary for a checklist row, or null when the item carries no actions. */
fun itemActionLabel(decisions: ReviewDecisions, settings: AppSettings): String? =
    settings.actions.filter { it.isSetOn(decisions) }
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
                customActions = readCustomActions(prefs),
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

    /**
     * Appends an action, named from the first free slot.
     *
     * Read-modify-write inside one edit, like every other change to this list, so two taps in
     * quick succession add two actions rather than racing to add the same one.
     */
    suspend fun addCustomAction() = put { prefs ->
        val existing = readCustomActions(prefs)
        prefs[KEY_CUSTOM_ACTIONS] =
            encodeCustomActions(existing + nextCustomActionDefaults(existing))
    }

    /** Renames one action, or changes what it sends. An unknown id writes nothing. */
    suspend fun updateCustomAction(
        id: String,
        displayName: String? = null,
        webhookValue: String? = null
    ) = put { prefs ->
        prefs[KEY_CUSTOM_ACTIONS] = encodeCustomActions(
            readCustomActions(prefs).map { action ->
                if (action.id != id) {
                    action
                } else {
                    action.copy(
                        displayName = displayName?.let(::sanitizeActionText) ?: action.displayName,
                        webhookValue = webhookValue?.let(::sanitizeActionText) ?: action.webhookValue
                    )
                }
            }
        )
    }

    /**
     * Takes an action off the bar.
     *
     * Items that carry it are not touched. The id stays on the rows that hold it, invisible and
     * unsent, because deleting a button is a decision about the bar and not about the files
     * somebody has already marked with it — and because this app does not quietly rewrite
     * decisions the user made.
     */
    suspend fun removeCustomAction(id: String) = put { prefs ->
        prefs[KEY_CUSTOM_ACTIONS] =
            encodeCustomActions(readCustomActions(prefs).filterNot { it.id == id })
    }

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

    /**
     * The configured actions, from the list if one has ever been written and from the two
     * original slots if not.
     *
     * The fallback is the upgrade path, and it is a read rather than a one-off rewrite on
     * purpose: an install that never opens this page keeps working off its old keys forever, and
     * the first change made here is what settles the new form. The ids match what the database
     * migration wrote onto the items, so a file marked C1 in the old world is still marked C1.
     */
    private fun readCustomActions(
        prefs: androidx.datastore.preferences.core.Preferences
    ): List<CustomAction> {
        prefs[KEY_CUSTOM_ACTIONS]?.let { return decodeCustomActions(it) }

        val legacy = DefaultCustomActions.mapIndexed { index, default ->
            val name = if (index == 0) prefs[KEY_C1_NAME] else prefs[KEY_C2_NAME]
            val value = if (index == 0) prefs[KEY_C1_VALUE] else prefs[KEY_C2_VALUE]
            default.copy(
                displayName = name ?: default.displayName,
                webhookValue = value ?: default.webhookValue
            )
        }
        return legacy
    }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_QUICK_REVIEW = booleanPreferencesKey("folder_quick_review_enabled")
        val KEY_CUSTOM_ACTIONS = stringPreferencesKey("custom_actions")

        // Read-only from here on: what an install configured before actions became a list.
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
