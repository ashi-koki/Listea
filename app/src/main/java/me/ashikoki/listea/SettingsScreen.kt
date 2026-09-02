package me.ashikoki.listea

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Stable, long-lived configuration, gathered out of the business screens.
 *
 * Composed from two stores on purpose: the browsing root stays in [FolderStore] because it is a
 * SAF grant that already persists correctly, and everything else lives in [SettingsStore]. There
 * is no benefit to moving the root just for architectural tidiness.
 *
 * Switches persist on the spot. Text fields persist when they lose focus or the user presses Done,
 * so a half-typed URL is never written and DataStore is not hit on every keystroke.
 *
 * V4.3 puts each group in the same [SectionCard] the management screens use, so this reads as a
 * page of the app rather than the settings form it used to be. One scroll surface, as everywhere.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: ListsViewModel = viewModel(),
    historyOpen: Boolean = false,
    onHistoryOpenChange: (Boolean) -> Unit = {}
) {
    // A page of its own rather than a section of this one: the payloads it holds are the app's,
    // not any one list's, and Settings is where the webhook they failed to reach is configured.
    if (historyOpen) {
        UnsentHistoryScreen(
            modifier = modifier,
            viewModel = viewModel,
            onBack = { onHistoryOpenChange(false) }
        )
        return
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val unsentCount by viewModel.unsentWebhookCount.collectAsStateWithLifecycle()
    val rootChangePreview by viewModel.rootChangePreview.collectAsStateWithLifecycle()
    val deleteRequest by viewModel.deleteCheckedRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember { FolderStore(context) }

    // Re-read after a change so the label follows the newly granted root.
    var rootGeneration by remember { mutableStateOf(0) }
    val rootUri = remember(rootGeneration) { store.read() }
    val rootLabel by produceState("No folder selected", rootUri) {
        value = rootUri?.let {
            withContext(Dispatchers.IO) { sourceFolderLabel(context, it, "") }
        } ?: "No folder selected"
    }

    // Bumped by anything that changes what a walk of the root would find, so the totals on screen
    // are not left describing files that are no longer there.
    var storageGeneration by remember { mutableStateOf(0) }

    // A full walk of the tree, so it is done off the main thread and reported as it goes: the
    // card says it is measuring rather than showing zeroes it is about to replace.
    val storage by produceState<StorageInfo>(StorageInfo.Scanning, rootUri, storageGeneration) {
        value = StorageInfo.Scanning
        val root = rootUri
        value = if (root == null) {
            StorageInfo.NoFolder
        } else {
            withContext(Dispatchers.IO) { scanFolderStats(context, root) }
                ?.let { StorageInfo.Ready(it) }
                ?: StorageInfo.Unavailable
        }
    }

    // Nothing is checked until a root is chosen, and there is nothing to observe either.
    val checkedFileCount by remember(rootUri) {
        rootUri?.let { viewModel.observeCheckedFileCount(it.toString()) } ?: flowOf(0)
    }.collectAsStateWithLifecycle(0)

    // Survives the picker's trip through another activity, so a confirmed change is still
    // remembered if this screen is recreated while the user is choosing.
    var discardRootOnPick by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val discard = discardRootOnPick
        discardRootOnPick = null
        // Backing out of the picker changes nothing at all: no new root, no deletions.
        if (uri == null) return@rememberLauncherForActivityResult
        // Write is taken alongside read because File management deletes through this same grant,
        // and a grant can only be persisted at the moment it is handed over. A tree that is only
        // offered read-only is still a perfectly good browsing root, so it is taken rather than
        // refused: what becomes unavailable is deleting, and the File management card says so.
        fun take(flags: Int) = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess

        val granted = take(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) || take(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (granted) {
            // The confirmed deletion happens here and only here — once a genuinely different
            // root has actually been granted. Re-picking the same folder deletes nothing.
            if (discard != null && discard != uri.toString()) {
                viewModel.discardListsForRoot(discard)
            }
            store.save(uri)
            rootGeneration++
        }
    }

    // Why the message survives the trip: the picker is another activity, and coming back to be
    // told nothing about what just happened would be the worst part of this flow.
    var grantNotice by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Asking for permission to change the root, which is its own trip through the picker and so
     * gets its own launcher.
     *
     * Deliberately not the one above: that one treats a different folder coming back as a root
     * change and deletes the Lists linked to the old root. Asking for access to the folder already
     * in use must never be able to reach that, so a folder that is not the current root is refused
     * here rather than acted on, and nothing at all is written unless the two match.
     */
    val grantWriteAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val root = rootUri
        // Backing out is a real answer: the dialog is still there to try again or cancel.
        if (uri == null || root == null) return@rememberLauncherForActivityResult

        if (uri != root) {
            grantNotice = "That is a different folder, so nothing was changed. Choose " +
                "$rootLabel itself — the folder Listea is already using — to allow deleting " +
                "from it."
            return@rememberLauncherForActivityResult
        }

        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (granted) {
            grantNotice = null
            // Straight back to where the user was, rather than making them find the button again.
            viewModel.requestCheckedFileDeletion(root.toString())
        } else {
            grantNotice = "Android did not allow changes to this folder. Somewhere in internal " +
                "storage, or a folder you picked yourself, will work."
        }
    }

    /** Choosing a first folder costs nothing; replacing one has to be confirmed. */
    fun startFolderChange() {
        val current = rootUri
        if (current == null) pickFolder.launch(null) else viewModel.previewRootChange(current.toString())
    }

    // One scroll surface for the whole page; no subsection scrolls on its own.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
    ) {
        Spacer(Modifier.height(ListeaDimens.CompactGap))

        SectionCard(title = "Storage") {
            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text("Root folder", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        rootLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // What is actually in there, counted the same way a List's scan counts it, so
                    // this and a folder-backed List can never disagree about what the root holds.
                    StatusLine(
                        storageSummary(storage),
                        tone = if (storage is StorageInfo.Unavailable) {
                            StatusTone.Warning
                        } else {
                            StatusTone.Neutral
                        },
                        maxLines = 2
                    )
                },
                actions = {
                    Button(onClick = { startFolderChange() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (rootUri == null) "Select folder" else "Change folder", maxLines = 1)
                    }
                }
            )
            // The lists that go are the ones linked to the folder being replaced. Nothing is
            // rebound to the new root, and manual lists are never touched.
            HelperText("Changing folder deletes the Lists linked to the current one. Manual Lists are kept.")
        }

        SectionCard(title = "Review") {
            SwitchRow(
                label = "Enable Quick Review in Folder",
                icon = Icons.Filled.Visibility,
                checked = settings.folderQuickReviewEnabled,
                onCheckedChange = { viewModel.setFolderQuickReviewEnabled(it) }
            )
            HelperText(
                "Off turns the Folder tab into a plain browser and gallery. Lists keep their " +
                    "items, completion and actions."
            )

            CustomActionFields(
                title = "Custom action 1",
                displayName = settings.custom1DisplayName,
                webhookValue = settings.custom1WebhookValue,
                onDisplayNameCommit = { viewModel.setCustom1DisplayName(it) },
                onWebhookValueCommit = { viewModel.setCustom1WebhookValue(it) }
            )
            CustomActionFields(
                title = "Custom action 2",
                displayName = settings.custom2DisplayName,
                webhookValue = settings.custom2WebhookValue,
                onDisplayNameCommit = { viewModel.setCustom2DisplayName(it) },
                onWebhookValueCommit = { viewModel.setCustom2WebhookValue(it) }
            )
            HelperText(
                "The display name is what you see on the chips. The webhook value is what future " +
                    "payloads send. Items already marked keep their selection."
            )
        }

        SectionCard(title = "Media and review behaviour") {
            SwitchRow(
                label = "Play videos automatically",
                icon = Icons.Filled.PlayArrow,
                checked = settings.videoAutoplay,
                onCheckedChange = { viewModel.setVideoAutoplay(it) }
            )
            SwitchRow(
                label = "Start videos muted",
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                checked = settings.videoStartMuted,
                onCheckedChange = { viewModel.setVideoStartMuted(it) }
            )
            SwitchRow(
                label = "Remember review position",
                icon = Icons.Outlined.Bookmark,
                checked = settings.rememberReviewPosition,
                onCheckedChange = { viewModel.setRememberReviewPosition(it) }
            )
            SwitchRow(
                label = "Auto-check list freshness on entry",
                icon = Icons.Filled.Refresh,
                checked = settings.autoCheckSourceFreshness,
                onCheckedChange = { viewModel.setAutoCheckSourceFreshness(it) }
            )
            HelperText(
                "Resuming applies to full Review only — Quick Review always starts at the first " +
                    "unchecked item. With auto-checking off, a List says \"Not checked\" until " +
                    "you use Update from folder."
            )

            SwitchRow(
                label = "Review unchecked items only",
                icon = Icons.Outlined.CheckCircle,
                checked = settings.reviewUncheckedOnly,
                onCheckedChange = { viewModel.setReviewUncheckedOnly(it) }
            )
            HelperText(
                "Review and Quick Review queue only the items that were unchecked when you " +
                    "opened them. The queue is fixed for that round, so an item you check stays " +
                    "in front of you until you leave."
            )
        }

        SectionCard(title = "Webhook") {
            SwitchRow(
                label = "Default enabled",
                checked = settings.defaultWebhookEnabled,
                onCheckedChange = { viewModel.setDefaultWebhookEnabled(it) }
            )
            SettingsTextField(
                label = "Default URL",
                value = settings.defaultWebhookUrl,
                onCommit = { viewModel.setDefaultWebhookUrl(it) },
                allowEmpty = true
            )
            HelperText(
                "Copied into new Lists when they are created — existing Lists are never changed " +
                    "— and used directly by anything that posts through the default webhook, " +
                    "which the switch above turns on and off."
            )

            SwitchRow(
                label = "Send from Quick Review",
                icon = Icons.Filled.Visibility,
                checked = settings.quickReviewWebhookEnabled,
                onCheckedChange = { viewModel.setQuickReviewWebhookEnabled(it) }
            )
            HelperText(
                "Finishing a Quick Review posts the folder's items through the default webhook " +
                    "above — Quick Review has no webhook of its own — so it needs Default " +
                    "enabled on and a URL filled in. Anything missing is reported rather than " +
                    "passing silently."
            )

            SwitchRow(
                label = "Send when leaving a review",
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                checked = settings.webhookOnReviewExit,
                onCheckedChange = { viewModel.setWebhookOnReviewExit(it) }
            )
            HelperText(
                "Leaving Review or Quick Review sends what that round covered. Review uses the " +
                    "List's own webhook; Quick Review uses the default webhook and also needs " +
                    "the switch above. A queue you finished on screen does not send twice, and a " +
                    "round with nothing in it is reported instead of sent."
            )

            SwitchRow(
                label = "Send checked items only",
                icon = Icons.Filled.FilterList,
                checked = settings.webhookCompletedItemsOnly,
                onCheckedChange = { viewModel.setWebhookCompletedItemsOnly(it) }
            )
            HelperText(
                "Every payload carries only the items that are checked, the test webhook " +
                    "included. With \"Review unchecked items only\" on, that is exactly the " +
                    "decisions of the round you just did. Nothing checked means nothing is sent, " +
                    "and Listea says so."
            )

            HorizontalDivider(Modifier.padding(top = ListeaDimens.CompactGap))

            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text("Unsent history", style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusLine(
                        if (unsentCount == 0) {
                            "Nothing waiting"
                        } else {
                            "$unsentCount payload" + (if (unsentCount == 1) "" else "s") +
                                " waiting to be sent"
                        },
                        tone = if (unsentCount == 0) StatusTone.Neutral else StatusTone.Warning
                    )
                },
                actions = {
                    Button(
                        onClick = { onHistoryOpenChange(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open", maxLines = 1)
                    }
                }
            )
            HelperText(
                "Every webhook that does not come back successful is kept here with its payload, " +
                    "so a round of review is never lost to a misconfigured endpoint. Resending " +
                    "uses the default URL above and needs nothing else switched on."
            )
        }

        SectionCard(title = "File management") {
            SwitchRow(
                label = "Same filter and sort everywhere",
                checked = settings.sharedFileArrangement,
                onCheckedChange = { viewModel.setSharedFileArrangement(it) },
                icon = Icons.Filled.FilterAlt
            )
            HelperText(
                "On, one filter and one sort order are shared by every folder and every list: " +
                    "set them once from a folder's Files header or a list's Items header and " +
                    "everywhere else follows. Off, each folder and each list keeps its own."
            )
            HelperText(
                "Filtering and sorting only change what is shown, and which items a review then " +
                    "walks. Progress, completion and webhooks always count the whole folder or " +
                    "the whole list."
            )
            HelperText(
                "Switching this discards nothing. Both the shared setting and every folder's and " +
                    "list's own are kept, so turning it on to sweep through a root and turning " +
                    "it back off returns each of them to what it had."
            )
            Spacer(Modifier.height(ListeaDimens.RowGap))

            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text("Checked files", style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusLine(
                        // Says "under this root" because that is genuinely what it counts: every
                        // checked file anywhere beneath the selected folder, whenever and however
                        // it was checked. It is not a list's progress and not a review round's
                        // tally, and reading it as either is the easiest mistake this number
                        // invites — the two will disagree the moment anything was checked
                        // elsewhere.
                        if (checkedFileCount == 0) {
                            "Nothing checked under this root"
                        } else {
                            countLabel(checkedFileCount, "checked file") +
                                " under this root, still on the device"
                        },
                        tone = if (checkedFileCount == 0) StatusTone.Neutral else StatusTone.Warning
                    )
                },
                actions = {
                    Button(
                        onClick = { viewModel.requestCheckedFileDeletion(rootUri?.toString()) },
                        enabled = rootUri != null && checkedFileCount > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete checked files", maxLines = 1)
                    }
                },
                // Wider than the other cards' action columns, because this button says what it
                // deletes rather than just "Delete" and a clipped destructive label is no label.
                sideBySideMinWidth = 380.dp,
                actionColumnWidth = 210.dp
            )
            HelperText(
                "Deletes every checked file in the current root folder from the device itself, " +
                    "permanently. You are shown the full list first and nothing happens until you " +
                    "confirm. The items stay in their Lists, marked as missing, so what you " +
                    "decided about them is kept."
            )
            HelperText(
                "Files are deleted where Listea last recorded them, so update a List from its " +
                    "folder first if things have been moved around outside the app."
            )
        }

        Spacer(Modifier.height(ListeaDimens.SectionGap))
    }

    // The totals in the Storage card describe files that have just stopped existing, so a
    // finished run sends the walk round again rather than leaving them to go quietly stale.
    LaunchedEffect(deleteRequest) {
        if (deleteRequest is DeleteCheckedRequest.Done) storageGeneration++
    }

    DeleteCheckedFilesDialog(
        request = deleteRequest,
        rootLabel = rootLabel,
        grantNotice = grantNotice,
        onDismiss = {
            grantNotice = null
            viewModel.dismissDeleteCheckedRequest()
        },
        onGrantAccess = {
            grantNotice = null
            // Opens on the folder already in use, so granting is one tap rather than a hunt.
            grantWriteAccess.launch(rootUri?.let(::initialPickerUri))
        },
        onConfirm = { viewModel.confirmDeleteCheckedFiles(rootUri?.toString()) }
    )

    RootChangeDialog(
        preview = rootChangePreview,
        currentRootLabel = rootLabel,
        onCancel = { viewModel.dismissRootChangePreview() },
        onConfirm = { pending ->
            viewModel.dismissRootChangePreview()
            discardRootOnPick = pending.rootUri
            // Starts where the current root is, since a replacement is usually near it. Landing
            // on it is not choosing it: nothing changes until a folder is confirmed.
            pickFolder.launch(rootUri?.let(::initialPickerUri))
        }
    )
}

/**
 * Spells out what changing the root costs before the picker ever opens: which lists go, what
 * survives, and that nothing happens until a new folder is actually chosen.
 */
@Composable
private fun RootChangeDialog(
    preview: RootChangePreview?,
    currentRootLabel: String,
    onCancel: () -> Unit,
    onConfirm: (RootChangePreview) -> Unit
) {
    val pending = preview ?: return
    val count = pending.titles.size

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Change root folder?") },
        text = {
            Column {
                if (count == 0) {
                    Text("No lists are linked to $currentRootLabel, so nothing will be deleted.")
                } else {
                    Text(
                        "${countLabel(count, "list")} linked to $currentRootLabel will be deleted, " +
                            "including any created in its subfolders. Their items, checked " +
                            "state and actions go with them. This cannot be undone."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    pending.titles.take(PREVIEW_TITLES).forEach { title ->
                        Text(
                            "• $title",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (count > PREVIEW_TITLES) {
                        Text(
                            "• and ${count - PREVIEW_TITLES} more",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    "Manual lists, and lists linked to any other folder, are kept. Nothing is " +
                        "deleted unless you go on to choose a new folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }) { Text("Choose new folder") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private const val PREVIEW_TITLES = 5

/**
 * What the Storage card knows about the selected root while it is finding out.
 *
 * Measuring a tree means walking all of it, which is not instant on a folder worth measuring, and
 * an unreadable folder is not an empty one. Both are states of their own so the card can say
 * which it is in rather than showing a plausible-looking zero.
 */
private sealed interface StorageInfo {
    data object NoFolder : StorageInfo
    data object Scanning : StorageInfo
    data object Unavailable : StorageInfo
    data class Ready(val stats: FolderStats) : StorageInfo
}

private fun storageSummary(info: StorageInfo): String = when (info) {
    StorageInfo.NoFolder -> "Nothing to measure yet"
    StorageInfo.Scanning -> "Measuring…"
    StorageInfo.Unavailable -> "This folder could not be read"
    is StorageInfo.Ready -> info.stats.summary
}

/**
 * The one destructive thing Listea does outside its own database, made as slow and as explicit as
 * it deserves to be.
 *
 * The listing is the point of the dialog. "Delete 84 files" is not something anyone can agree to
 * honestly, so every file is named, under the folder that holds it, and the whole thing scrolls —
 * nothing is summarised away with "and 79 more", because the 79 are exactly what is at stake.
 * Grouping by folder keeps that readable at the length it can reach.
 *
 * Cancel is the easy path: it is the dismiss button, tapping outside works, and the delete button
 * is the one that has to be aimed for. While the deletion is running there is no way out at all,
 * because there is nothing left to decide and a half-finished run should not look like a choice.
 */
@Composable
private fun DeleteCheckedFilesDialog(
    request: DeleteCheckedRequest?,
    rootLabel: String,
    grantNotice: String?,
    onDismiss: () -> Unit,
    onGrantAccess: () -> Unit,
    onConfirm: () -> Unit
) {
    val pending = request ?: return

    AlertDialog(
        onDismissRequest = { if (pending !is DeleteCheckedRequest.Deleting) onDismiss() },
        title = {
            Text(
                when (pending) {
                    DeleteCheckedRequest.Scanning -> "Checked files"
                    DeleteCheckedRequest.NothingChecked -> "Nothing to delete"
                    DeleteCheckedRequest.NeedsWriteAccess -> "Allow Listea to delete files?"
                    is DeleteCheckedRequest.Confirm -> "Delete checked files?"
                    DeleteCheckedRequest.Deleting -> "Deleting"
                    is DeleteCheckedRequest.Done -> "Finished"
                    is DeleteCheckedRequest.Error -> "Nothing was deleted"
                }
            )
        },
        text = {
            when (pending) {
                DeleteCheckedRequest.Scanning -> Text("Looking for checked files…")

                DeleteCheckedRequest.NothingChecked -> Text(
                    "No checked item in this folder still has a file behind it, so there is " +
                        "nothing to delete."
                )

                DeleteCheckedRequest.NeedsWriteAccess -> Column {
                    Text(
                        "Listea can read $rootLabel but is not allowed to change it yet. " +
                            "Android only hands that over through its own folder picker, so tap " +
                            "Grant access and confirm $rootLabel again — the picker opens on it " +
                            "already on most devices."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Nothing is deleted by granting this, and your Lists, items and settings " +
                            "are not touched. You are brought straight back here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    grantNotice?.let {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        StatusLine(it, tone = StatusTone.Warning, maxLines = 4)
                    }
                }

                is DeleteCheckedRequest.Confirm ->
                    CheckedFileListing(pending, rootLabel)

                DeleteCheckedRequest.Deleting -> Text("Removing the files from the device…")

                is DeleteCheckedRequest.Done -> Column {
                    Text(deleteOutcomeMessage(pending.deleted, pending.failed))
                    if (pending.deleted > 0) {
                        Spacer(Modifier.height(ListeaDimens.RowGap))
                        Text(
                            "Their items are kept and marked as missing, so what you decided " +
                                "about them is not lost.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is DeleteCheckedRequest.Error -> Text(pending.message)
            }
        },
        confirmButton = {
            when (pending) {
                is DeleteCheckedRequest.Confirm -> TextButton(onClick = onConfirm) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
                DeleteCheckedRequest.NeedsWriteAccess -> TextButton(onClick = onGrantAccess) {
                    Text("Grant access")
                }
                // Nothing to offer mid-run: the files are already going.
                DeleteCheckedRequest.Deleting -> Unit
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            val cancellable = pending is DeleteCheckedRequest.Confirm ||
                pending is DeleteCheckedRequest.NeedsWriteAccess
            if (cancellable) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Every file that would go, as folder-then-files, in a box of its own that scrolls.
 *
 * Lazy because the list is as long as the user's review has been: a folder library can put
 * thousands of names in here, and composing all of them to show the first twenty would stall the
 * dialog exactly when it must not. Monospace so a run of similar filenames stays scannable.
 */
@Composable
private fun CheckedFileListing(pending: DeleteCheckedRequest.Confirm, rootLabel: String) {
    Column {
        Text(
            "${countLabel(pending.fileCount, "file")} will be deleted from this device " +
                "permanently. This cannot be undone."
        )
        Spacer(Modifier.height(ListeaDimens.RowGap))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ListingMaxHeight),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            items(pending.groups, key = { it.folderPath }) { group ->
                Column {
                    Text(
                        group.folderPath.ifEmpty { rootLabel },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        group.filesLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Tall enough to be worth scrolling, short enough that the dialog stays a dialog. */
private val ListingMaxHeight = 320.dp

/**
 * One custom action's two fields: what it is called, and what it sends.
 *
 * Side by side where both stay readable, stacked where they would not. The threshold is about the
 * fields rather than the device: a text field narrower than this stops showing enough of its own
 * value to edit it, and two cramped fields are worse than two full-width ones.
 */
@Composable
private fun CustomActionFields(
    title: String,
    displayName: String,
    webhookValue: String,
    onDisplayNameCommit: (String) -> Unit,
    onWebhookValueCommit: (String) -> Unit
) {
    Spacer(Modifier.height(ListeaDimens.RowGap))
    Text(title, style = MaterialTheme.typography.titleSmall)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= CustomActionSideBySideMinWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
                SettingsTextField(
                    label = "Display name",
                    value = displayName,
                    onCommit = onDisplayNameCommit,
                    modifier = Modifier.weight(1f)
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = webhookValue,
                    onCommit = onWebhookValueCommit,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column {
                SettingsTextField(
                    label = "Display name",
                    value = displayName,
                    onCommit = onDisplayNameCommit
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = webhookValue,
                    onCommit = onWebhookValueCommit
                )
            }
        }
    }
}

/** Two 150dp fields and the gap between them: below this a field stops showing enough to edit. */
private val CustomActionSideBySideMinWidth = 308.dp

/** The quiet line under a control that explains it, in one place so they all match. */
@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A settings text field that commits on Done, on losing focus, or on leaving the screen — never
 * per keystroke.
 *
 * Leaving counts as finishing the edit, and has to: tapping a button does not move focus away
 * from a text field on touch, so typing a URL and going straight to another page used to discard
 * it without a word, and the next delivery would quietly still use the old one. What the user
 * typed is what gets stored; what it means is decided when it is used.
 *
 * Blank input is refused rather than stored: an empty action name would leave an unlabelled chip
 * and an empty wire value would put `""` in a webhook array. The field says so and the stored
 * value is left alone. [allowEmpty] is for the default URL, where empty legitimately means "none".
 */
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false
) {
    // Seeded per stored value so an external change lands, without fighting the keyboard.
    var text by remember(value) { mutableStateOf(value) }
    val error = if (allowEmpty) null else settingsTextError(text)

    fun commit() {
        if (error == null && text.trim() != value) onCommit(text)
    }

    // Held through [rememberUpdatedState] so disposal writes what is in the field at that moment,
    // not whatever it held when the effect was set up. Committing something already stored is a
    // no-op, so a field that lost focus first is not written twice.
    val commitOnLeave by rememberUpdatedState(::commit)
    DisposableEffect(Unit) { onDispose { commitOnLeave() } }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}
