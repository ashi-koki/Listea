package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** What the Folder screen is currently quick-reviewing. */
data class QuickReviewTarget(val listId: Long, val folderPath: String)

/**
 * Folder-scoped review: the direct files of one browsed folder, backed by the real items of the
 * list that owns it.
 *
 * This is not a second review system. It is a narrower queue handed to the same [ReviewSession],
 * so gestures, actions, the media renderer and the completion path are literally the same code.
 * Every edit lands on the owning list's actual items, which is why a completion reached here
 * fires the list completion webhook exactly as one reached anywhere else does.
 *
 * The queue deliberately excludes subfolders, manual items and vanished sources, and the list's
 * own persisted review position is never read or written — Quick Review is temporary, and starts
 * at the first unchecked item every time it opens.
 */
@Composable
fun QuickReviewScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    target: QuickReviewTarget,
    onExit: () -> Unit
) {
    val detailFlow = remember(target.listId) { viewModel.observeDetail(target.listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)

    BackHandler { onExit() }

    val folderName = quickReviewFolderName(target.folderPath)

    val current = detail
    if (current == null) {
        ReviewFrame(modifier, "Quick Review", backLabel = "‹ Folder", onBack = onExit) {
            CircularProgressIndicator()
        }
        return
    }

    // One filter over the items already loaded for the owning list: no query per file.
    val queue = remember(current.items, current.list.sourceRelativePath, target.folderPath) {
        directSourceItems(
            items = current.items,
            scopePath = current.list.sourceRelativePath.orEmpty(),
            folderPath = target.folderPath
        )
    }

    if (queue.isEmpty()) {
        // Subfolders only, or direct files the list has not taken in yet. Nothing is pulled from
        // deeper folders to avoid this: an empty folder-scoped queue is simply empty.
        ReviewFrame(modifier, "Quick Review", backLabel = "‹ Folder", onBack = onExit) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No items to review in this folder",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    folderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onExit) { Text("Exit") }
            }
        }
        return
    }

    val completed = queue.count { it.isCompleted }
    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = queue,
        // Reopening on a different folder is a different session; leaving discards it entirely,
        // which is what makes every entry start at the first unchecked item again.
        sessionKey = "quick:${target.listId}:${target.folderPath}",
        chrome = ReviewChrome(
            context = "Quick Review · " + folderName,
            // Scoped counts, never the owning list's overall total.
            subtitle = "Quick Review · " + folderName + " · " +
                progressLabel(completed, queue.size, completed == queue.size),
            backLabel = "‹ Folder",
            completeHeadline = "Quick Review complete",
            exitLabel = "Exit"
        ),
        // Null start point and a no-op position sink: the list's own review position is neither
        // read nor written here, so normal Review resumes exactly where it always would.
        persistedItemId = null,
        onPositionChanged = {},
        onRestart = null,
        onBack = onExit
    )
}

/** The browsed folder's own name, or the root's stand-in when the path is the root itself. */
private fun quickReviewFolderName(folderPath: String): String =
    folderPath.substringAfterLast('/').ifEmpty { "Selected folder" }
