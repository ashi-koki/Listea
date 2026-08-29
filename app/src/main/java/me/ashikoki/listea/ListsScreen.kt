package me.ashikoki.listea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.ashikoki.listea.data.ListSummary

@Composable
fun ListsScreen(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = viewModel(),
    requestedListId: Long? = null,
    onRequestConsumed: () -> Unit = {}
) {
    var openListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewListId by rememberSaveable { mutableStateOf<Long?>(null) }

    // The Folder tab can ask for a specific list to be opened.
    LaunchedEffect(requestedListId) {
        if (requestedListId != null) {
            openListId = requestedListId
            onRequestConsumed()
        }
    }

    val reviewing = reviewListId
    val listId = openListId
    when {
        reviewing != null -> ReviewScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = reviewing,
            onBack = { reviewListId = null }
        )

        listId == null -> ListsIndex(
            modifier = modifier,
            viewModel = viewModel,
            onOpenList = { openListId = it }
        )

        else -> ListDetailScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = listId,
            onBack = { openListId = null },
            onReview = { reviewListId = listId }
        )
    }
}

@Composable
private fun ListsIndex(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onOpenList: (Long) -> Unit
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListSummary?>(null) }
    var deleting by remember { mutableStateOf<ListSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Lists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = { creating = true }) { Text("New list") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        if (summaries.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("No lists yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(summaries, key = { it.id }) { summary ->
                    ListRow(
                        summary = summary,
                        onOpen = { onOpenList(summary.id) },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        TextPromptDialog(
            title = "New list",
            label = "List name",
            initialText = "",
            confirmLabel = "Create",
            onConfirm = { viewModel.createList(it); creating = false },
            onDismiss = { creating = false }
        )
    }

    renaming?.let { target ->
        TextPromptDialog(
            title = "Rename list",
            label = "List name",
            initialText = target.title,
            confirmLabel = "Rename",
            onConfirm = { viewModel.renameList(target.id, it); renaming = null },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete list") },
            text = { Text("Delete \"${target.title}\" and its ${target.totalItems} items?") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteList(target.id); deleting = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ListRow(
    summary: ListSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(summary.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                progressLabel(summary.completedItems, summary.totalItems, summary.isComplete),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                webhookStatusLabel(
                    enabled = summary.webhookEnabled,
                    lastDeliveryStatus = summary.lastDeliveryStatus,
                    lastDeliveryCode = summary.lastDeliveryCode
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Text("⋮", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}

/** "3 / 5 complete", with a tick once the whole list is done. */
fun progressLabel(completed: Int, total: Int, isComplete: Boolean): String =
    "$completed / $total complete" + if (isComplete) " ✓" else ""

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initialText: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) })
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
