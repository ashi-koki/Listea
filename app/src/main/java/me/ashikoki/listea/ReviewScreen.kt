package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil3.ImageLoader
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity

/**
 * The text and exits that distinguish one review context from another. Everything else — the
 * queue mechanics, gestures, actions and media renderer — is shared.
 *
 * [context] names where the queue came from and belongs in the secondary line, never in the
 * header: the header belongs to the item on screen, and it reads the same whether the user
 * arrived from a list or from a folder. Back already knows the way home.
 */
data class ReviewChrome(
    val context: String,
    val subtitle: String,
    val backLabel: String,
    val completeHeadline: String,
    val exitLabel: String
)

/**
 * Everything known about the item on screen, gathered in one place for a future Info surface.
 *
 * V3.9 builds the model, not the sheet. Size and modified time are nullable because nothing is
 * stored for them yet and no per-item file access is done to find out; the rest is already at
 * hand and is simply no longer thrown away.
 */
data class ReviewItemInfo(
    val title: String,
    val relativePath: String?,
    val sourceUri: String?,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val sourceMissing: Boolean,
    val isCompleted: Boolean,
    val actionLabel: String?,
    val listId: Long,
    val listTitle: String?
)

/** Assembles what the current card knows about itself. Cheap: no queries, no file access. */
fun reviewItemInfo(
    item: ListItemEntity,
    settings: AppSettings,
    listTitle: String?
): ReviewItemInfo = ReviewItemInfo(
    title = item.title,
    relativePath = item.sourceRelativePath,
    sourceUri = item.sourceUri,
    sizeBytes = null,
    lastModified = null,
    sourceMissing = item.sourceMissing,
    isCompleted = item.isCompleted,
    actionLabel = itemActionLabel(item, settings),
    listId = item.listId,
    listTitle = listTitle
)

/**
 * Sequential one-item-at-a-time review of a whole list, in its stored order.
 *
 * Swipe left  = mark the current item completed, then advance.
 * Swipe right = step back only; it never changes any completion state.
 *
 * Position is tracked by item id and persisted on the list, so re-sync, cleanup and deletions
 * cannot leave it pointing at the wrong item.
 */
@Composable
fun ReviewScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)

    BackHandler { onBack() }

    val current = detail
    if (current == null) {
        ReviewFrame(modifier, title = "", backLabel = "‹ List", onBack = onBack) {
            CircularProgressIndicator()
        }
        return
    }

    val items = current.items
    if (items.isEmpty()) {
        ReviewFrame(modifier, current.list.title, backLabel = "‹ List", onBack = onBack) {
            Text("This list has no items to review.")
        }
        return
    }

    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = items,
        sessionKey = listId,
        chrome = ReviewChrome(
            context = current.list.title,
            subtitle = current.list.title + " · " +
                progressLabel(current.completedCount, items.size, current.isComplete),
            backLabel = "‹ List",
            completeHeadline = "Review complete",
            exitLabel = "Back to list"
        ),
        persistedItemId = current.list.reviewCurrentItemId,
        onPositionChanged = { viewModel.setReviewPosition(listId, it) },
        onRestart = { viewModel.setReviewPosition(listId, items.first().id) },
        onBack = onBack
    )
}

/**
 * The review experience itself, over whatever queue it is handed: the whole list for normal
 * Review, one folder's direct files for Quick Review. There is one implementation of the
 * gestures, the action row and the media renderer, and one underlying item state — both modes
 * edit the same real [ListItemEntity] rows through the same ViewModel calls, so completion,
 * actions and the list completion webhook behave identically wherever the swipe came from.
 *
 * [persistedItemId] is the resume point, or null to always start at the first unchecked item.
 * [onPositionChanged] is where a mode decides whether moving is worth remembering: Quick Review
 * passes an empty lambda, which is what leaves the list's own review position untouched.
 * A null [onRestart] hides the restart offer on the completion screen.
 */
@Composable
fun ReviewSession(
    modifier: Modifier,
    viewModel: ListsViewModel,
    items: List<ListItemEntity>,
    sessionKey: Any,
    chrome: ReviewChrome,
    persistedItemId: Long?,
    onPositionChanged: (Long) -> Unit,
    onRestart: (() -> Unit)?,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var currentItemId by rememberSaveable(sessionKey) { mutableStateOf<Long?>(null) }
    var finished by rememberSaveable(sessionKey) { mutableStateOf(false) }

    // One player for the whole screen rather than one per card, so nothing leaks between items.
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val imageLoader = remember(context) { buildImageLoader(context) }

    // Resolve the start position, and repair it if the item it pointed at has gone.
    LaunchedEffect(items, currentItemId) {
        if (items.none { it.id == currentItemId }) {
            currentItemId = resumeItemId(persistedItemId, items)
        }
    }

    val index = items.indexOfFirst { it.id == currentItemId }
    if (index < 0) {
        ReviewFrame(modifier, chrome.context, chrome.backLabel, onBack) { CircularProgressIndicator() }
        return
    }

    if (finished) {
        ReviewFinished(
            modifier = modifier,
            headline = chrome.completeHeadline,
            title = chrome.context,
            completed = items.count { it.isCompleted },
            total = items.size,
            exitLabel = chrome.exitLabel,
            onBack = onBack,
            onRestart = onRestart?.let { restart ->
                {
                    finished = false
                    currentItemId = items.first().id
                    restart()
                }
            }
        )
        return
    }

    val item = items[index]

    fun goTo(newIndex: Int) {
        val id = items[newIndex].id
        currentItemId = id
        onPositionChanged(id)
    }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(chrome.backLabel) }
            // The filename, held to one line: names get long, and the media is what the screen is
            // for. The full value stays available through reviewItemInfo for a future Info action.
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${index + 1} / ${items.size}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            chrome.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        SwipeCard(
            key = item.id,
            // Forward is never blocked: swiping the last item off checks it and ends the review.
            canSwipeForward = true,
            canSwipeBack = index > 0,
            onSwipeForward = {
                // The existing completion path, so a list that becomes complete here fires the
                // webhook exactly as a manual checkbox tick would — from either review mode.
                viewModel.setItemCompleted(item, true)
                if (index == items.lastIndex) finished = true else goTo(index + 1)
            },
            onSwipeBack = { goTo(index - 1) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ItemPreview(
                item = item,
                player = player,
                imageLoader = imageLoader,
                settings = settings,
                modifier = Modifier.fillMaxSize()
            )
        }

        HorizontalDivider()
        Column(Modifier.padding(top = 8.dp)) {
            // The filename is in the header now; what is left here is the metadata V4 will fold
            // behind an Info action.
            item.sourceRelativePath?.takeIf { it != item.title }?.let { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ReviewActionRow(
                item = item,
                // Observed here rather than threaded through every caller: renaming an action in
                // Settings relabels the chips immediately, with no restart and no item touched.
                settings = settings,
                onToggleCompleted = { viewModel.setItemCompleted(item, it) },
                onToggleAction = { action, enabled ->
                    viewModel.setItemAction(item, action, enabled)
                }
            )
            Text(
                "Swipe left to check and continue · swipe right to go back",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Completion toggle plus the three review actions, for the current item only.
 *
 * It lives below the card rather than on it, outside the Box that owns the horizontal drag
 * detector, so a tap here is never seen by the swipe gesture and can never navigate.
 *
 * The completion chip goes through the same [ListsViewModel.setItemCompleted] path as the
 * checkbox and the left swipe, so checking the last item here fires the completion webhook
 * exactly as it would anywhere else. The action chips only persist a flag: no navigation, no
 * completion change, no delivery.
 */
@Composable
private fun ReviewActionRow(
    item: ListItemEntity,
    settings: AppSettings,
    onToggleCompleted: (Boolean) -> Unit,
    onToggleAction: (ItemAction, Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = item.isCompleted,
            onClick = { onToggleCompleted(!item.isCompleted) },
            label = { Text(if (item.isCompleted) "Checked" else "Not checked") }
        )
        // Fixed order, so the row never reshuffles as actions are toggled.
        ItemAction.entries.forEach { action ->
            val isSet = action.isSetOn(item)
            FilterChip(
                selected = isSet,
                onClick = { onToggleAction(action, !isSet) },
                label = { Text(settings.labelOf(action)) }
            )
        }
    }
}

/**
 * Resume rules: a still-valid persisted item wins, even if it is already checked. Otherwise start
 * at the first unchecked item, and if everything is checked, at the very first item. Passing a
 * null [persisted] is how Quick Review always starts at the first unchecked item.
 */
private fun resumeItemId(persisted: Long?, items: List<ListItemEntity>): Long {
    if (persisted != null && items.any { it.id == persisted }) return persisted
    return (items.firstOrNull { !it.isCompleted } ?: items.first()).id
}

@Composable
fun ReviewFrame(
    modifier: Modifier,
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ReviewFinished(
    modifier: Modifier,
    headline: String,
    title: String,
    completed: Int,
    total: Int,
    exitLabel: String,
    onBack: () -> Unit,
    onRestart: (() -> Unit)?
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(headline, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$completed / $total processed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text(exitLabel) }
            onRestart?.let { restart ->
                OutlinedButton(onClick = restart) { Text("Review again") }
            }
        }
    }
}

/**
 * Places one list item in front of the shared [MediaPreview].
 *
 * Every item gets a card: nothing is filtered out of the queue, so an item with no source or a
 * source that has vanished falls through to a placeholder and stays swipeable. Anything that does
 * have a readable source is handed to the same renderer the Folder browser uses.
 */
@Composable
private fun ItemPreview(
    item: ListItemEntity,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    modifier: Modifier
) {
    val uri = item.sourceUri
    if (uri == null) {
        PreviewPlaceholder(modifier, "Manual item", item.title)
        return
    }
    if (item.sourceMissing) {
        PreviewPlaceholder(modifier, "Source missing", item.title)
        return
    }

    MediaPreview(
        uri = uri,
        name = item.title,
        player = player,
        imageLoader = imageLoader,
        modifier = modifier,
        videoAutoplay = settings.videoAutoplay,
        videoStartMuted = settings.videoStartMuted
    )
}
