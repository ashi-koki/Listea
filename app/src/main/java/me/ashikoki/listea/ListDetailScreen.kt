package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.FolderDiff
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ReviewItem

@Composable
fun ListDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit,
    onReview: () -> Unit = {}
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    var newItem by remember { mutableStateOf("") }
    // Held out here, above every early return, rather than left to the LazyColumn to remember.
    // Opening an item replaces this whole page with the viewer, so a state created down inside
    // the list would leave the composition with it and the user would come back to the top of a
    // list they had scrolled a long way down.
    val itemsScroll = rememberLazyListState()
    // Which item is being looked at, if any. An id rather than the item itself, so the viewer
    // keeps following the same row as the list around it is re-read.
    var viewingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sortOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    // The viewer owns Back while it is up, and says so explicitly rather than relying on which
    // handler happened to be registered last.
    BackHandler(enabled = viewingItemId == null) { onBack() }

    // One check per page entry, plus one whenever the app returns to the foreground while this
    // page is the one on screen: another app (a file manager, FolderSync, ...) may have added or
    // deleted source files while we were away.
    //
    // Adding the observer dispatches ON_RESUME immediately to bring it up to the current state,
    // and that first dispatch is the entry check — so this single effect covers both triggers
    // without checking twice. Keyed on listId as well as the owner, which LifecycleEventEffect
    // cannot do, so switching lists re-registers and re-checks. Ordinary recomposition —
    // checkboxes, actions, database emissions — does neither. Placed before the loading return
    // so the check is tied to entering the page, not to data arriving.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, listId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.checkSourceFreshness(listId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val freshness by viewModel.sourceFreshness.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    // The same two sources the Folder browser reads, under this list's own key, so "same filter
    // and sort everywhere" really does mean everywhere and a list is not a third place to set it.
    val arrangementKey = listArrangementKey(listId)
    val arrangement = resolveArrangement(arrangements, arrangementKey, settings.sharedFileArrangement)

    val current = detail
    if (current == null) {
        ListeaNestedScaffold(
            modifier = modifier,
            title = "",
            backLabel = "Lists",
            onBack = onBack
        ) { bodyModifier ->
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    // What the filter and the sort leave on screen. Re-derived whenever the items, the
    // arrangement or any checked state changes — all three arrive here as a new input rather than
    // as something to invalidate by hand, which is what makes checking an item off move it
    // straight into its new group.
    //
    // Deliberately not used for anything but display and the queue a Review walks. The counts
    // above, the progress bar and the completion this list reports all read `current` — see the
    // second rule at the top of ReviewArrange.kt.
    val shownItems = remember(current.items, arrangement) {
        arrangeFiles(current.items, arrangement, System.currentTimeMillis(), ::reviewFacts)
    }

    // Opened over the page rather than beside it: the viewer is the whole screen, and returning
    // from it has to land on the same list, scrolled where it was.
    viewingItemId?.let { openedId ->
        ListItemViewerScreen(
            modifier = modifier,
            // The items the page is showing, in the order it shows them, so paging through the
            // viewer and scrolling the page walk the same sequence. A filtered page pages its
            // subset: the viewer opened from a row that is on screen, and reaching one that is
            // not by swiping would be the filter failing to mean anything.
            items = shownItems,
            initialItemId = openedId,
            listTitle = current.list.title,
            settings = settings,
            onClose = { viewingItemId = null }
        )
        return
    }

    fun submitNewItem() {
        viewModel.addItem(listId, newItem)
        newItem = ""
    }

    ListeaNestedScaffold(
        modifier = modifier,
        title = current.list.title,
        backLabel = "Lists",
        onBack = onBack,
        trailing = {
            Text(
                progressLabel(current.completedCount, current.items.size, current.isComplete),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.isComplete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    ) { bodyModifier ->
        // One scroll surface for the whole page. Expanding the webhook panel pushes the items
        // down instead of squeezing them into a fixed viewport, and the add-item row sits at the
        // end of what it adds to rather than permanently reserving the bottom of the screen.
        LazyColumn(
            state = itemsScroll,
            modifier = bodyModifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ListeaDimens.PagePadding,
                vertical = ListeaDimens.RowGap
            )
        ) {
            // 1. Source — only folder-backed lists have one to reconcile against.
            val rootUri = current.list.sourceRootUri
            val relativePath = current.list.sourceRelativePath
            if (rootUri != null && relativePath != null) {
                item {
                    SourceCard(
                        rootUri = rootUri,
                        relativePath = relativePath,
                        missingCount = current.items.count { it.sourceMissing },
                        // Only this list's own verdict: another list's result is ignored outright.
                        freshness = freshness?.takeIf { it.listId == listId }?.state,
                        onResync = { viewModel.requestResync(listId) },
                        onClearMissing = { viewModel.requestMissingCleanup(listId) }
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                }
            }

            // 2. Progress — the count, the way into Review, and the webhook completion drives.
            item {
                ProgressCard(
                    detail = current,
                    onReview = onReview,
                    webhook = {
                        WebhookSection(
                            list = current.list,
                            onEnabledChange = { viewModel.setWebhookEnabled(listId, it) },
                            onUrlChange = { viewModel.setWebhookUrl(listId, it) },
                            onTest = { viewModel.testWebhook(listId) }
                        )
                    }
                )
                Spacer(Modifier.height(ListeaDimens.SectionGap))
            }

            // 3. Items — ListItems only. A folder-backed list holds its source files, however
            // deep they sit; the folders they came from are not items and never appear here.
            item {
                // The header keeps the list's whole item count behind it: the controls that
                // decide what is shown must not themselves disappear when the filter they set
                // happens to match nothing.
                ArrangeableSectionHeader(
                    title = "Items",
                    arrangement = arrangement,
                    onSort = { sortOpen = true },
                    onFilter = { filterOpen = true },
                    noun = "items"
                )
                if (!arrangement.isFilterDefault) {
                    FilterSummary(
                        shown = shownItems.size,
                        total = current.items.size,
                        noun = "item"
                    )
                }
                Spacer(Modifier.height(ListeaDimens.CompactGap))
                HorizontalDivider()
            }
            if (shownItems.isEmpty()) {
                item {
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        if (current.items.isEmpty()) "No items yet" else "No items match the filter",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(shownItems, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        settings = settings,
                        onToggle = { viewModel.setItemCompleted(item, it) },
                        onOpen = { viewingItemId = item.id },
                        onDelete = { item.rowId?.let { viewModel.deleteItem(listId, it) } }
                    )
                    HorizontalDivider()
                }
            }

            // 4. Add item, at the end of what it adds to — scrolled to, never pinned over it.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ListeaDimens.SectionGap),
                    horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newItem,
                        onValueChange = { newItem = it },
                        label = { Text("New item") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitNewItem() })
                    )
                    Button(onClick = { submitNewItem() }, enabled = newItem.isNotBlank()) {
                        Text("Add")
                    }
                }
            }
        }
    }

    fun applyArrangement(updated: FileArrangement) {
        viewModel.setArrangement(arrangementKey, updated)
        sortOpen = false
        filterOpen = false
    }

    // Both groups are always offered here, unlike on the Folder screen where they follow the
    // review integration switch. A List has had checkboxes since before any of this existed, so
    // filtering and sorting on them can never be a control with invisible consequences.
    if (sortOpen) {
        FileSortDialog(
            arrangement = arrangement,
            showCheckedSort = true,
            onDismiss = { sortOpen = false },
            onApply = ::applyArrangement,
            noun = "items"
        )
    }
    if (filterOpen) {
        FileFilterDialog(
            arrangement = arrangement,
            showCheckedGroup = true,
            onDismiss = { filterOpen = false },
            onApply = ::applyArrangement,
            noun = "items"
        )
    }

    ResyncDialogs(viewModel)
}

@Composable
private fun ResyncDialogs(viewModel: ListsViewModel) {
    val request by viewModel.resyncRequest.collectAsStateWithLifecycle()

    when (val current = request) {
        null -> Unit

        is ResyncRequest.Scanning -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking folder") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(ListeaDimens.SectionGap))
                    Text("Comparing this list with its source folder.")
                }
            },
            confirmButton = {}
        )

        is ResyncRequest.UpToDate -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("List is up to date") },
            text = { Text("Nothing has changed in the source folder.") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )

        is ResyncRequest.Confirm -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Folder changes detected") },
            text = {
                val diff = current.diff
                Column {
                    Text(countLine(diff.addedPaths.size, "new file"))
                    Text(countLine(diff.missingPaths.size, "missing file"))
                    if (diff.restoredPaths.isNotEmpty()) {
                        Text(countLine(diff.restoredPaths.size, "restored file"))
                    }
                    Text("${diff.unchangedCount} unchanged")
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    diff.addedPaths.take(PREVIEW_PATHS).forEach { Text("+ $it") }
                    diff.missingPaths.take(PREVIEW_PATHS).forEach { Text("- $it") }
                    Text(
                        "Missing files stay in the list and keep their checked state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmResync() }) { Text("Update list") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Cancel") }
            }
        )

        is ResyncRequest.CleanupMissing -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Remove missing items?") },
            text = {
                Column {
                    Text(
                        countLine(current.missingCount, "item") +
                            if (current.missingCount == 1) {
                                " no longer exists in the source folder."
                            } else {
                                " no longer exist in the source folder."
                            }
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    Text(
                        "Keeping them preserves their checked state. Removing them cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRemoveMissingItems() }) {
                    Text("Remove ${countLine(current.missingCount, "item")}")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("Keep items") }
            }
        )

        is ResyncRequest.Error -> AlertDialog(
            onDismissRequest = { viewModel.dismissResyncRequest() },
            title = { Text("Could not refresh") },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResyncRequest() }) { Text("OK") }
            }
        )
    }
}

private const val PREVIEW_PATHS = 5

private fun countLine(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/**
 * How far along the list is, and the two things that act on that: Review, and the webhook that a
 * completion delivers. The count speaks for itself — no "in progress" restating what "12 / 20"
 * already says.
 *
 * The webhook sits at the bottom of this card and expands in place. Because the whole page is one
 * scroll surface, opening it pushes the items down the page rather than squeezing them into
 * whatever height is left over.
 */
@Composable
private fun ProgressCard(
    detail: ListDetail,
    onReview: () -> Unit,
    webhook: @Composable () -> Unit
) {
    SectionCard(title = "Progress") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Text(
                progressLabel(detail.completedCount, detail.items.size, detail.isComplete),
                style = MaterialTheme.typography.titleMedium,
                color = if (detail.isComplete) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (detail.items.isNotEmpty()) {
                Button(onClick = onReview) { Text("Review", maxLines = 1) }
            }
        }
        // An empty list has nothing to be a fraction of, so it gets no bar rather than an
        // ambiguous empty one.
        if (detail.items.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { detail.completedCount.toFloat() / detail.items.size },
                modifier = Modifier.fillMaxWidth()
            )
        }
        HorizontalDivider(Modifier.padding(top = ListeaDimens.CompactGap))
        webhook()
    }
}

/**
 * Where a folder-backed list came from, its source freshness, the manual update action, and —
 * whenever any source is missing — a standing cleanup action. The cleanup offer shown right
 * after an update can be dismissed, and a later update will report "up to date" because the items
 * are already flagged, so this button is the way back to it.
 *
 * Freshness is reported here and nowhere else: detecting changes never opens a dialog by itself.
 * The user is told, and decides when to act. An up-to-date source is three short lines, because
 * the ordinary case is the one that should cost the least room.
 */
@Composable
private fun SourceCard(
    rootUri: String,
    relativePath: String,
    missingCount: Int,
    freshness: SourceFreshness?,
    onResync: () -> Unit,
    onClearMissing: () -> Unit
) {
    val context = LocalContext.current
    val label by produceState(relativePath, rootUri, relativePath) {
        value = withContext(Dispatchers.IO) {
            sourceFolderLabel(context, rootUri.toUri(), relativePath)
        }
    }

    SectionCard(title = "Source") {
        InfoActionsRow(
            // Wider than the folder card's column: "Update from folder" is the widest label in
            // the app, and shortening it would make it say something else.
            sideBySideMinWidth = 340.dp,
            actionColumnWidth = 200.dp,
            info = {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            },
            actions = {
                // Unacknowledged changes promote the action to a filled button: hard to miss,
                // but still just a button. "Update", not "check": checking already happened on
                // entry.
                if (freshness is SourceFreshness.ChangesAvailable) {
                    Button(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                        Text("Update from folder", maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = onResync, modifier = Modifier.fillMaxWidth()) {
                        Text("Update from folder", maxLines = 1)
                    }
                }
                // A standing offer, not a warning: it is a button like its neighbour, tinted
                // because removing items is the one thing on this card that cannot be undone.
                if (missingCount > 0) {
                    OutlinedButton(
                        onClick = onClearMissing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear $missingCount missing", maxLines = 1)
                    }
                }
            }
        )
        // Full width below both columns: the change summary names everything that actually
        // changed, and is the one line here that must not be squeezed into half a card.
        freshness?.let { FreshnessLine(it) }
    }
}

/** Compact, inline, never modal: the page stays usable while a scan is running. */
@Composable
private fun FreshnessLine(state: SourceFreshness) {
    val text = when (state) {
        SourceFreshness.Checking -> "Checking source…"
        SourceFreshness.NotChecked -> "Not checked — automatic checking is off"
        SourceFreshness.UpToDate -> "Up to date"
        SourceFreshness.SourceUnavailable -> "Source unavailable"
        is SourceFreshness.ChangesAvailable -> "Changes detected · " + changesSummary(state.diff)
    }
    val tone = when (state) {
        SourceFreshness.Checking, SourceFreshness.NotChecked -> StatusTone.Neutral
        SourceFreshness.UpToDate -> StatusTone.Positive
        else -> StatusTone.Warning
    }
    val icon = when (state) {
        SourceFreshness.Checking -> null
        SourceFreshness.NotChecked -> Icons.AutoMirrored.Filled.HelpOutline
        SourceFreshness.UpToDate -> Icons.Filled.CheckCircle
        SourceFreshness.SourceUnavailable -> Icons.Filled.ErrorOutline
        is SourceFreshness.ChangesAvailable -> Icons.Filled.Warning
    }
    // Two lines, because the change summary names everything that actually changed and must not
    // be cut off at the point where it stops being actionable.
    StatusLine(text, tone = tone, icon = icon, maxLines = 2)
}

/**
 * "3 new files · 1 missing file", naming only what actually changed.
 *
 * Counts newly missing files rather than every missing path: files already acknowledged as
 * missing are not news, and reporting them here would contradict the up-to-date verdict a list
 * holding them is entitled to.
 */
private fun changesSummary(diff: FolderDiff): String = buildList {
    if (diff.addedPaths.isNotEmpty()) add(countLine(diff.addedPaths.size, "new file"))
    if (diff.newlyMissingCount > 0) add(countLine(diff.newlyMissingCount, "missing file"))
    if (diff.restoredPaths.isNotEmpty()) add(countLine(diff.restoredPaths.size, "restored file"))
}.joinToString(" · ")

/**
 * The list's optional completion action. Collapsed by default so the items stay the focus.
 * Nothing here sends anything on its own: delivery is triggered by the ViewModel when the
 * database reports a real incomplete -> complete transition, or by the Test button.
 */
@Composable
private fun WebhookSection(
    list: ListEntity,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onTest: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Seeded once per list so incoming database updates never fight with typing.
    var urlText by remember(list.id) { mutableStateOf(list.webhookUrl) }
    val urlProblem = webhookUrlError(urlText)

    // The shared browse-row wording, minus the prefix the header already provides: one
    // description of webhook state, formatted once, wherever it is shown.
    val status = webhookStatusLabel(
        enabled = list.webhookEnabled,
        lastDeliveryStatus = list.lastDeliveryStatus,
        lastDeliveryCode = list.lastDeliveryCode
    ).removePrefix("Webhook · ")

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = ListeaDimens.RowGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            Text("Webhook", style = MaterialTheme.typography.titleSmall)
            StatusLine(status, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Send when this list is completed",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = list.webhookEnabled, onCheckedChange = onEnabledChange)
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it; onUrlChange(it) },
                label = { Text("POST URL") },
                singleLine = true,
                isError = urlText.isNotBlank() && urlProblem != null,
                supportingText = urlProblem?.let { problem -> { Text(problem) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            ActionGroup {
                OutlinedButton(onClick = onTest, enabled = urlProblem == null) {
                    Text("Test webhook", maxLines = 1)
                }
                StatusLine(deliveryLabel(list))
            }
        }
    }
}

/**
 * One item: check it, look at it, or remove it. Opening it is a tap on the text.
 *
 * The tap used to open a rename box and now opens the item itself — see [ListItemViewerScreen]
 * for why renaming a folder-backed item was never really an edit. The tappable area is the text
 * column alone, so the checkbox and the delete button keep their own meanings and a tap aimed at
 * either cannot fall through to opening the viewer.
 *
 * The tags are read-only here — actions are set in Review — and carry whatever names Settings
 * currently gives them, which is why [settings] is passed in rather than the labels.
 */
@Composable
private fun ItemRow(
    item: ReviewItem,
    settings: AppSettings,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.decisions.isCompleted, onCheckedChange = onToggle)
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = ListeaDimens.RowGap),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration =
                    if (item.decisions.isCompleted) TextDecoration.LineThrough else null,
                color = if (item.decisions.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Folder-backed items keep their path so bilibili/a.jpg stays distinct from
            // danbooru/a.jpg.
            item.relativePath?.takeIf { it != item.title }?.let { path ->
                StatusLine(path)
            }
            ItemActionTags(item, settings)
            // Subtle: the item stays checkable and is never hidden or removed.
            if (item.sourceMissing) {
                StatusLine(
                    "Source missing",
                    tone = StatusTone.Warning,
                    icon = Icons.Filled.ErrorOutline
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete item",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
    }
}

/**
 * The item's tags: Favourite, and the two custom slots, one badge each.
 *
 * One badge per action rather than a single joined string, because these are three independent
 * flags and the row is read at a glance — "★  Save" as one line invites reading it as one label.
 * Favourite is tinted, the custom slots are not: the star is the only one of the three whose
 * meaning is fixed, and the other two say whatever Settings has named them.
 *
 * Emits nothing at all for an item with no actions set, so an untagged list stays quiet.
 */
@Composable
private fun ItemActionTags(item: ReviewItem, settings: AppSettings) {
    val tags = ItemAction.entries.filter { it.isSetOn(item.decisions) }
    if (tags.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
        tags.forEach { action ->
            ListeaBadge(
                text = settings.labelOf(action),
                container = if (action == ItemAction.FAVORITE) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (action == ItemAction.FAVORITE) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
