package me.ashikoki.listea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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

/**
 * The Lists destination and the two screens nested under it.
 *
 * Which nested screen is open is owned by the shell — it decides whether the bottom navigation
 * belongs on screen — but the switching itself stays here, next to the screens it switches
 * between.
 */
@Composable
fun ListsScreen(
    modifier: Modifier = Modifier,
    viewModel: ListsViewModel = viewModel(),
    openListId: Long? = null,
    onOpenListChange: (Long?) -> Unit = {},
    reviewListId: Long? = null,
    onReviewListChange: (Long?) -> Unit = {}
) {
    when {
        reviewListId != null -> ReviewScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = reviewListId,
            onBack = { onReviewListChange(null) }
        )

        openListId == null -> ListsIndex(
            modifier = modifier,
            viewModel = viewModel,
            onOpenList = { onOpenListChange(it) }
        )

        else -> ListDetailScreen(
            modifier = modifier,
            viewModel = viewModel,
            listId = openListId,
            onBack = { onOpenListChange(null) },
            onReview = { onReviewListChange(openListId) }
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
    var filter by rememberSaveable { mutableStateOf(ListFilter.All) }

    val visible = remember(summaries, filter) { summaries.filter(filter::accepts) }

    // The screen title lives in the shell's app bar now; this group is the filters and the one
    // action the index owns. It wraps rather than overflowing, so "New list" cannot be pushed
    // off the right edge of a narrow portrait screen by three filter chips.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = ListeaDimens.PagePadding)
    ) {
        ActionGroup(Modifier.padding(vertical = ListeaDimens.RowGap)) {
            ListFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label, maxLines = 1) }
                )
            }
            Button(onClick = { creating = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ListeaDimens.IconSize)
                )
                Spacer(Modifier.width(ListeaDimens.CompactGap))
                Text("New list", maxLines = 1)
            }
        }
        HorizontalDivider()

        if (visible.isEmpty()) {
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            Text(
                if (summaries.isEmpty()) "No lists yet" else "No lists in this filter",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = ListeaDimens.RowGap),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
            ) {
                items(visible, key = { it.id }) { summary ->
                    ListCard(
                        summary = summary,
                        onOpen = { onOpenList(summary.id) },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary }
                    )
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

/**
 * One list, as little as the index needs to say about it: what it is called, how far along it is,
 * and a way to rename or delete it.
 *
 * Nothing here reports source freshness or offers Review — an index is for choosing which list to
 * work on, and both of those belong to the list you have chosen. The whole card is the way in.
 *
 * Every list reports its webhook, including one that has none configured. "Off" is a real answer
 * to "will finishing this send anything?", and leaving the line out entirely made the index look
 * as though it had failed to load rather than as though the answer were no.
 */
@Composable
private fun ListCard(
    summary: ListSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ListeaDimens.CardPadding,
                    top = ListeaDimens.RowGap,
                    bottom = ListeaDimens.RowGap
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLine(
                    progressLabel(summary.completedItems, summary.totalItems, summary.isComplete),
                    tone = if (summary.isComplete) StatusTone.Positive else StatusTone.Neutral
                )
                StatusLine(
                    webhookStatusLabel(
                        enabled = summary.webhookEnabled,
                        lastDeliveryStatus = summary.lastDeliveryStatus,
                        lastDeliveryCode = summary.lastDeliveryCode
                    )
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
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
}

/**
 * Completion as a filter, which is the only place the words belong: the rows themselves just
 * carry numbers.
 */
enum class ListFilter(val label: String) {
    All("All"),
    InProgress("In progress"),
    Completed("Completed");

    fun accepts(summary: ListSummary): Boolean = when (this) {
        All -> true
        InProgress -> !summary.isComplete
        Completed -> summary.isComplete
    }
}

/**
 * "3 / 5", with a tick once the whole thing is done.
 *
 * Just the numbers: "complete" after every count reads as a status when it is only ever a unit,
 * and completion already has its own marker and its own filter.
 */
fun progressLabel(completed: Int, total: Int, isComplete: Boolean): String =
    "$completed / $total" + if (isComplete) " ✓" else ""

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
