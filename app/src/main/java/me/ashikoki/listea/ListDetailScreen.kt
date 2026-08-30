package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import me.ashikoki.listea.data.ListDetail
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity

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
    var editing by remember { mutableStateOf<ListItemEntity?>(null) }

    BackHandler { onBack() }

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

    val current = detail
    if (current == null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = onBack) { Text("‹ Lists") }
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        return
    }

    fun submitNewItem() {
        viewModel.addItem(listId, newItem)
        newItem = ""
    }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Lists") }
            Text(
                current.list.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))

        // 1. Source — only folder-backed lists have one to reconcile against.
        val rootUri = current.list.sourceRootUri
        val relativePath = current.list.sourceRelativePath
        if (rootUri != null && relativePath != null) {
            SourceSection(
                rootUri = rootUri,
                relativePath = relativePath,
                missingCount = current.items.count { it.sourceMissing },
                // Only this list's own verdict: a result for any other list is ignored outright.
                freshness = freshness?.takeIf { it.listId == listId }?.state,
                onResync = { viewModel.requestResync(listId) },
                onClearMissing = { viewModel.requestMissingCleanup(listId) }
            )
            Spacer(Modifier.height(12.dp))
        }

        // 2. Progress — the count, the way into Review, and the webhook that completion drives.
        ProgressSection(
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
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        // 3. Items — ListItems only. A folder-backed list holds its source files, however deep
        // they sit; the folders they came from are not items and never appear here.
        if (current.items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("No items yet", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(current.items, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        actionLabel = itemActionLabel(item, settings),
                        onToggle = { viewModel.setItemCompleted(item, it) },
                        onEdit = { editing = item },
                        onDelete = { viewModel.deleteItem(item) }
                    )
                    HorizontalDivider()
                }
            }
        }

        // 4. Add item, at the end of what it adds to.
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Button(onClick = { submitNewItem() }, enabled = newItem.isNotBlank()) { Text("Add") }
        }
    }

    editing?.let { target ->
        TextPromptDialog(
            title = "Edit item",
            label = "Item text",
            initialText = target.title,
            confirmLabel = "Save",
            onConfirm = { viewModel.renameItem(target.id, it); editing = null },
            onDismiss = { editing = null }
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
                    Spacer(Modifier.width(16.dp))
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
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(8.dp))
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
 */
@Composable
private fun ProgressSection(
    detail: ListDetail,
    onReview: () -> Unit,
    webhook: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                Button(onClick = onReview) { Text("Review") }
            }
        }
        Spacer(Modifier.height(8.dp))
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
 * The user is told, and decides when to act.
 */
@Composable
private fun SourceSection(
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

    Column(Modifier.fillMaxWidth()) {
        Text("Source", style = MaterialTheme.typography.labelMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        freshness?.let { FreshnessLine(it) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Unacknowledged changes promote the action to a filled button: hard to miss, but
            // still just a button. "Update", not "check": checking already happened on entry.
            if (freshness is SourceFreshness.ChangesAvailable) {
                Button(onClick = onResync) { Text("Update from folder") }
            } else {
                TextButton(onClick = onResync) { Text("Update from folder") }
            }
            if (missingCount > 0) {
                TextButton(onClick = onClearMissing) {
                    Text(
                        "Clear $missingCount missing",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Compact, inline, never modal: the page stays usable while a scan is running. */
@Composable
private fun FreshnessLine(state: SourceFreshness) {
    val text = when (state) {
        SourceFreshness.Checking -> "Checking source…"
        SourceFreshness.UpToDate -> "✓ Up to date"
        SourceFreshness.SourceUnavailable -> "! Source unavailable"
        is SourceFreshness.ChangesAvailable -> "! Changes detected · " + changesSummary(state.diff)
    }
    val color = when (state) {
        SourceFreshness.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
        SourceFreshness.UpToDate -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
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

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Webhook",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (list.webhookEnabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.bodyMedium)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onTest, enabled = urlProblem == null) {
                    Text("Test webhook")
                }
                Text(
                    deliveryLabel(list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ListItemEntity,
    actionLabel: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                color = if (item.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            // Folder-backed items keep their path so bilibili/a.jpg stays distinct from danbooru/a.jpg.
            item.sourceRelativePath?.takeIf { it != item.title }?.let { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Read-only here: actions are set in Review, this row just reports them, under
            // whatever names Settings currently gives them.
            actionLabel?.let { actions ->
                Text(
                    actions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Subtle: the item stays checkable and is never hidden or removed.
            if (item.sourceMissing) {
                Text(
                    "Source missing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        IconButton(onClick = onDelete) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
