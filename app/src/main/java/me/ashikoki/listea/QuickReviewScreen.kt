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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ashikoki.listea.data.ReviewItem

/** What the Folder screen is currently quick-reviewing: a folder, under the root it sits in. */
data class QuickReviewTarget(val rootUri: String, val folderPath: String)

/**
 * Folder-scoped review: the direct files of one browsed folder.
 *
 * This is not a second review system, and it is no longer a view of a first one either. It resolves
 * the folder's own files, attaches the decisions already held about them, and hands that queue to
 * the same [ReviewSession] full Review uses — so gestures, actions, the media renderer and the
 * webhook path are literally the same code.
 *
 * No List is involved at any point. Nothing is created, nothing is replaced, and a List that
 * happens to cover this folder is not consulted, not scanned and not written to. It will still
 * show every decision made here the instant it is made, because a decision belongs to the file and
 * both are reading the same state — and if that decision completes it, its webhook fires exactly
 * as it would have from its own page.
 *
 * The queue is one folder's direct files, narrowed and ordered by the filter and sort the folder
 * page was showing: pick a subset there, press Quick Review, and that subset is what gets edited.
 * Subfolders are places to navigate to, not items. Nothing persists a position here — Quick Review
 * is temporary and starts at the first unchecked item every time it opens.
 */
@Composable
fun QuickReviewScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    target: QuickReviewTarget,
    onExit: () -> Unit
) {
    val reviewFlow = remember(target) {
        viewModel.observeFolderReview(target.rootUri, target.folderPath)
    }
    val review by reviewFlow.collectAsStateWithLifecycle(initialValue = FolderReview.Resolving)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    val folderName = quickReviewFolderName(target.folderPath)
    val sessionKey = "quick:${target.rootUri}:${target.folderPath}"

    // Read here rather than carried in from the Folder screen, and read from the same two sources
    // that screen reads: the arrangement is stored per folder (or shared), so both sides resolve
    // the same value and there is no third copy to fall out of step with the header controls.
    val arrangement = resolveArrangement(
        arrangements,
        folderArrangementKey(target.rootUri, target.folderPath),
        settings.sharedFileArrangement
    )

    when (val current = review) {
        FolderReview.Resolving -> {
            BackHandler { onExit() }
            ReviewFrame(modifier, "Quick Review", onBack = onExit) { CircularProgressIndicator() }
        }

        FolderReview.Unavailable -> {
            BackHandler { onExit() }
            QuickReviewNotice(
                modifier = modifier,
                headline = "This folder could not be read",
                detail = folderName,
                onExit = onExit
            )
        }

        is FolderReview.Ready -> QuickReviewQueue(
            modifier = modifier,
            viewModel = viewModel,
            target = target,
            folderName = folderName,
            sessionKey = sessionKey,
            files = current.items,
            arrangement = arrangement,
            uncheckedOnly = settings.reviewUncheckedOnly,
            onExit = onExit
        )
    }
}

/**
 * The resolved folder, once there is something to review or a reason there is not.
 *
 * Split from the state handling above so the round is only snapshotted when a real queue exists:
 * [rememberRoundQueue] freezes the round on entry, and entering that composition with an empty
 * list while the folder is still being read would freeze nothing at all.
 */
@Composable
private fun QuickReviewQueue(
    modifier: Modifier,
    viewModel: ListsViewModel,
    target: QuickReviewTarget,
    folderName: String,
    sessionKey: String,
    files: List<ReviewItem>,
    arrangement: FileArrangement,
    uncheckedOnly: Boolean,
    onExit: () -> Unit
) {
    val now = rememberRoundClock(sessionKey)

    // The subset picked out on the Folder screen, in the order it was shown there, minus anything
    // the unchecked-only setting removes. The very same call a List's Review makes: the two modes
    // differ in where the queue came from, not in what a filter and a sort do to one.
    val queue = rememberReviewRound(files, sessionKey, arrangement, uncheckedOnly, now)

    // Set when finishing the queue delivers, so leaving afterwards does not deliver a second time.
    val delivered = rememberSessionDelivered(sessionKey)
    val leave = leaveReview(
        queue = queue,
        delivered = delivered,
        onLeave = { queued ->
            viewModel.onFolderReviewExited(target.rootUri, target.folderPath, queued)
        },
        onBack = onExit
    )
    BackHandler { leave() }

    if (queue.isEmpty()) {
        // A folder of nothing but subfolders, a filter that matched none of its files, or a
        // folder that has already been reviewed. Nothing is pulled from deeper folders and no
        // filter is quietly relaxed to avoid this: an empty round is simply empty, and saying
        // which of the three emptied it is the only way the user can tell what to change.
        //
        // The filter is asked on its own here rather than inferred from "a filter is set",
        // because a filter that matched ten files whose reviews are all done is a different
        // situation from one that matched none, and only one of them is fixed by changing it.
        val matched = files.count { matchesFilter(reviewFacts(it), arrangement, now) }
        QuickReviewNotice(
            modifier = modifier,
            headline = when {
                files.isEmpty() -> "No files to review in this folder"
                matched == 0 -> "No files match this folder's filter"
                arrangement.isFilterDefault -> "Every file in this folder is already checked"
                else -> "Every file this folder's filter keeps is already checked"
            },
            detail = folderName,
            onExit = leave
        )
        return
    }

    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = queue,
        // Reopening on a different folder is a different session; leaving discards it entirely,
        // which is what makes every entry start at the first unchecked item again.
        sessionKey = sessionKey,
        chrome = ReviewChrome(
            context = "Quick Review · " + folderName,
            // No list to name: these decisions land on the files themselves, and claiming a list
            // here would be claiming something about where they went.
            listTitle = null,
            completeHeadline = "Quick Review complete",
            exitLabel = "Exit",
            // The folder's numbers, not the round's. A filtered round that reaches its end has
            // not checked off the folder, and saying it had would be the one lie that matters.
            progress = ReviewProgress(
                completed = files.count { it.decisions.isCompleted },
                total = files.size
            )
        ),
        // Null start point and a no-op position sink: a folder round is temporary and has nowhere
        // to resume from, so normal Review resumes exactly where it always would.
        persistedItemId = null,
        onPositionChanged = {},
        onRestart = null,
        // Finishing a folder's queue is Quick Review's own completion, and the only moment it
        // has to report. Whether that actually sends is the ViewModel's decision.
        onQueueFinished = { queued ->
            delivered.value = true
            viewModel.onQuickReviewFinished(target.rootUri, target.folderPath, queued)
        },
        onBack = leave
    )
}

/** A folder with nothing to show, said plainly, with the one thing to do about it. */
@Composable
private fun QuickReviewNotice(
    modifier: Modifier,
    headline: String,
    detail: String,
    onExit: () -> Unit
) {
    ReviewFrame(modifier, "Quick Review", onBack = onExit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(ListeaDimens.RowGap))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            Button(onClick = onExit) { Text("Exit") }
        }
    }
}

/** The browsed folder's own name, or the root's stand-in when the path is the root itself. */
private fun quickReviewFolderName(folderPath: String): String =
    folderPath.substringAfterLast('/').ifEmpty { "Selected folder" }
