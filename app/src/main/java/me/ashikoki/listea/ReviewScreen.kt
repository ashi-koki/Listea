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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import coil3.ImageLoader
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity

/**
 * The few strings that distinguish one review context from another. Everything else — the queue
 * mechanics, the gestures, the action bar, the media renderer and the Info sheet — is shared.
 *
 * Nothing in here reaches the media surface itself. The top bar says what the item is and where
 * you are in the queue, and it reads the same whether you arrived from a list or from a folder;
 * [context] and [listTitle] are for the completion screen and the Info sheet, which is where
 * saying which queue this is belongs.
 */
data class ReviewChrome(
    /** What finished, on the completion screen: a list's title, or the folder being reviewed. */
    val context: String,
    /** The list every edit here actually lands on, named for the Info sheet. */
    val listTitle: String,
    val completeHeadline: String,
    val exitLabel: String
)

/**
 * Everything the Info sheet says about the item on screen that Listea already knows.
 *
 * All of it is on the [ListItemEntity] the queue is already holding, so building this costs
 * nothing and can happen per card. What is *not* here — type, size, modified time — is not stored
 * anywhere and is read from the provider by [MediaFacts], once, when the sheet opens.
 */
data class ReviewItemInfo(
    val title: String,
    val relativePath: String?,
    val sourceUri: String?,
    val sourceMissing: Boolean,
    val isCompleted: Boolean,
    val actionLabel: String?,
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
    sourceMissing = item.sourceMissing,
    isCompleted = item.isCompleted,
    actionLabel = itemActionLabel(item, settings),
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    val current = detail
    if (current == null) {
        ReviewFrame(modifier, title = "", onBack = onBack) { CircularProgressIndicator() }
        return
    }

    val items = current.items
    if (items.isEmpty()) {
        ReviewFrame(modifier, current.list.title, onBack = onBack) {
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
            listTitle = current.list.title,
            completeHeadline = "Review complete",
            exitLabel = "Back to list"
        ),
        // The setting decides what is read on entry, not what is written: the position keeps
        // being recorded below either way, so switching resuming back on picks up where Review
        // actually got to rather than wherever it was when it was switched off.
        persistedItemId = current.list.reviewCurrentItemId
            .takeIf { settings.rememberReviewPosition },
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
    // Not saved: reopening Info after a rotation would be answering a question nobody asked.
    var infoOpen by remember { mutableStateOf(false) }
    // Driven only by the player's own fullscreen button, and dropped whenever the card changes:
    // swiping onto a still image with the chrome hidden would leave nothing to swipe back with.
    var fullscreen by remember { mutableStateOf(false) }

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
        ReviewFrame(modifier, chrome.context, onBack) { CircularProgressIndicator() }
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
    LaunchedEffect(item.id) { fullscreen = false }
    FullscreenSystemBars(fullscreen)
    BackHandler(enabled = fullscreen) { fullscreen = false }

    fun goTo(newIndex: Int) {
        val id = items[newIndex].id
        currentItemId = id
        onPositionChanged(id)
    }

    MediaShell(
        modifier = modifier,
        title = item.title,
        // Where you are in this queue, which for Quick Review is the folder's items and not the
        // owning list's total. Completion progress is a list's business and lives on its page.
        position = "${index + 1} / ${items.size}",
        onBack = onBack,
        chromeVisible = !fullscreen,
        media = {
            SwipeCard(
                key = item.id,
                // Forward is never blocked: swiping the last item off checks it and ends the
                // review.
                canSwipeForward = true,
                canSwipeBack = index > 0,
                onSwipeForward = {
                    // The existing completion path, so a list that becomes complete here fires
                    // the webhook exactly as a manual checkbox tick would — from either mode.
                    viewModel.setItemCompleted(item, true)
                    if (index == items.lastIndex) finished = true else goTo(index + 1)
                },
                onSwipeBack = { goTo(index - 1) },
                modifier = Modifier.fillMaxSize()
            ) {
                ItemPreview(
                    item = item,
                    player = player,
                    imageLoader = imageLoader,
                    settings = settings,
                    isFullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        controls = {
            ReviewActionBar(
                item = item,
                // Observed here rather than threaded through every caller: renaming an action in
                // Settings relabels the chips immediately, with no restart and no item touched.
                settings = settings,
                onToggleCompleted = { viewModel.setItemCompleted(item, it) },
                onToggleAction = { action, enabled ->
                    viewModel.setItemAction(item, action, enabled)
                },
                onInfo = { infoOpen = true }
            )
        }
    )

    if (infoOpen) {
        ReviewInfoSheet(
            info = reviewItemInfo(item, settings, chrome.listTitle),
            onDismiss = { infoOpen = false }
        )
    }
}

/**
 * Everything the media surface stopped saying about the item in front of the user.
 *
 * The list-side facts are already in memory on the [ListItemEntity], so [reviewItemInfo] costs
 * nothing. Type, size and modified time are not stored anywhere and are read once, here, while
 * the sheet is open — never per card and never while swiping.
 */
@Composable
private fun ReviewInfoSheet(info: ReviewItemInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val facts by produceState(MediaFacts(), info.sourceUri) {
        value = info.sourceUri?.let { readMediaFacts(context, it) } ?: MediaFacts()
    }

    ItemInfoSheet(
        title = info.title,
        onDismiss = onDismiss,
        rows = buildList {
            info.relativePath?.let { add(InfoField("Path in source folder", it)) }
            info.listTitle?.let { add(InfoField("List", it)) }
            add(InfoField("Status", if (info.isCompleted) "Checked" else "Not checked"))
            add(InfoField("Actions", info.actionLabel ?: "None"))
            when {
                info.sourceUri == null -> add(InfoField("Source", "Manual item — no file"))
                info.sourceMissing ->
                    add(InfoField("Source", "Missing from the source folder"))
            }
            addAll(mediaFactRows(facts, info.title))
        }
    )
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

/**
 * Loading, and the queues that turn out to have nothing in them.
 *
 * Same thin bar as the media screens so entering a review never jumps between two layouts, minus
 * the position, because there is no queue to be anywhere in yet.
 */
@Composable
fun ReviewFrame(
    modifier: Modifier,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(end = ListeaDimens.PagePadding)
            )
        }
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
        Spacer(Modifier.height(ListeaDimens.RowGap))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$completed / $total processed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(ListeaDimens.SectionGap))
        Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
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
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
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
        videoStartMuted = settings.videoStartMuted,
        isFullscreen = isFullscreen,
        onFullscreenChange = onFullscreenChange
    )
}
