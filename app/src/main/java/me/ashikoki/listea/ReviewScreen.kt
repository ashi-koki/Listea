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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
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
import me.ashikoki.listea.data.ReviewItem

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
    /**
     * The list the queue came from, named for the Info sheet. Null for a folder review, which
     * came from no list: its decisions land on the files, and the sheet says nothing rather
     * than naming a list that had no part in it.
     */
    val listTitle: String?,
    val completeHeadline: String,
    val exitLabel: String,
    /** What the completion screen reports, which is never the round's own arithmetic. */
    val progress: ReviewProgress
)

/**
 * How far along the *whole* list or folder is, passed in rather than counted from the queue.
 *
 * The distinction only shows up once a round can be narrowed, and then it shows up sharply: a
 * hundred-item list filtered down to fifty and reviewed to the end is fifty of a hundred done, not
 * fifty of fifty. Counting the queue would have reported a finished list every time, which is the
 * one thing the completion screen must never say wrongly — so the count comes from whoever owns
 * the whole set, and this type exists to make handing it over the only way to get one.
 */
data class ReviewProgress(val completed: Int, val total: Int)

/**
 * Everything the Info sheet says about the item on screen that Listea already knows.
 *
 * All of it is on the [ReviewItem] the queue is already holding, so building this costs
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

/**
 * Assembles what the current card knows about itself. Cheap: no queries, no file access.
 *
 * [listTitle] is null for a folder review, which lands on no list at all — the Info sheet says so
 * rather than naming one, because naming one would be a claim about where the decision went.
 */
fun reviewItemInfo(
    item: ReviewItem,
    settings: AppSettings,
    listTitle: String?
): ReviewItemInfo = ReviewItemInfo(
    title = item.title,
    relativePath = item.relativePath,
    sourceUri = item.sourceUri,
    sourceMissing = item.sourceMissing,
    isCompleted = item.decisions.isCompleted,
    actionLabel = itemActionLabel(item.decisions, settings),
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
    val arrangements by viewModel.arrangements.collectAsStateWithLifecycle()

    // Read here rather than carried in from the detail page, and read from the same two sources
    // that page reads, so both resolve the same value with no third copy to drift.
    val arrangement = resolveArrangement(
        arrangements,
        listArrangementKey(listId),
        settings.sharedFileArrangement
    )

    val current = detail
    if (current == null) {
        BackHandler { onBack() }
        ReviewFrame(modifier, title = "", onBack = onBack) { CircularProgressIndicator() }
        return
    }

    val items = current.items
    val now = rememberRoundClock(listId)
    // Fixed for this round, so checking something off does not pull it out from under the user,
    // and so the subset picked out on the detail page is what this round walks.
    val queue = rememberReviewRound(items, listId, arrangement, settings.reviewUncheckedOnly, now)

    // A round begins here, and again on "Review again": whatever completed before now belongs to
    // an earlier one and must not be allowed to silence this one's report.
    LaunchedEffect(listId) { viewModel.onReviewStarted(listId) }

    // Set when finishing the queue reports the round, so leaving afterwards does not report it
    // twice. Whether anything is actually sent is the ViewModel's decision.
    val delivered = rememberSessionDelivered(listId)
    val leave = leaveReview(
        queue = queue,
        delivered = delivered,
        onLeave = { queued -> viewModel.onReviewExited(listId, queued.map { it.id }) },
        onBack = onBack
    )
    BackHandler { leave() }

    if (queue.isEmpty()) {
        ReviewFrame(modifier, current.list.title, onBack = leave) {
            // Which of the three emptied it, because only one of them is fixed by changing the
            // filter and the user cannot tell them apart from an empty screen.
            val matched = items.count { matchesFilter(reviewFacts(it), arrangement, now) }
            Text(
                when {
                    items.isEmpty() -> "This list has no items to review."
                    matched == 0 -> "No items match this list's filter."
                    arrangement.isFilterDefault -> "Every item in this list is already checked."
                    else -> "Every item this list's filter keeps is already checked."
                }
            )
        }
        return
    }

    ReviewSession(
        modifier = modifier,
        viewModel = viewModel,
        items = queue,
        sessionKey = listId,
        chrome = ReviewChrome(
            context = current.list.title,
            listTitle = current.list.title,
            completeHeadline = "Review complete",
            exitLabel = "Back to list",
            // The list's own numbers, not the round's: a filtered round that finishes has not
            // finished the list, and the page it returns to will still say so.
            progress = ReviewProgress(current.completedCount, items.size)
        ),
        // The setting decides what is read on entry, not what is written: the position keeps
        // being recorded below either way, so switching resuming back on picks up where Review
        // actually got to rather than wherever it was when it was switched off.
        persistedItemId = current.list.reviewCurrentItemId
            .takeIf { settings.rememberReviewPosition },
        onPositionChanged = { viewModel.setReviewPosition(listId, it) },
        onRestart = {
            viewModel.setReviewPosition(listId, queue.first().id)
            // A new round, which reports for itself: re-open it and let it deliver again.
            viewModel.onReviewStarted(listId)
            delivered.value = false
        },
        // Reported the moment the queue is done rather than waiting for the user to leave the
        // completion screen. The round is over either way, and a report that has already left the
        // device cannot be lost to the app being killed while that screen sits there.
        onQueueFinished = { queued ->
            delivered.value = true
            viewModel.onReviewExited(listId, queued.map { it.id })
        },
        onBack = leave
    )
}

/**
 * The queue a review actually walks: the page's filter and sort, then the unchecked-only setting,
 * snapshotted once and held for the round.
 *
 * The narrowing is a snapshot by item id, deliberately not a live filter. Checking an item is the
 * normal way through a review, and a live filter would make the current card vanish under the
 * user's thumb, renumber the queue and take back the ability to swipe backwards. Leaving and
 * re-entering is what starts the next round.
 *
 * Frozen even when nothing is being narrowed, because the *order* is part of what is being held:
 * it lives in the snapshot and nowhere else, and would be lost the moment the database re-emitted.
 * The cost is that an item added to the list mid-round joins the next round rather than this one,
 * which is the same promise the filter makes — this round is over the set that was there when it
 * started.
 *
 * Taken on the first load that has anything in it, because the items arrive from the database a
 * frame later than the screen does and snapshotting an empty list would queue nothing at all.
 * Kept across rotation with the rest of the session, and dropped with it on the way out.
 */
@Composable
fun rememberReviewRound(
    items: List<ReviewItem>,
    sessionKey: Any,
    arrangement: FileArrangement,
    uncheckedOnly: Boolean,
    now: Long
): List<ReviewItem> = rememberRoundQueue(
    items = items,
    sessionKey = sessionKey,
    narrowed = true,
    round = {
        if (items.isEmpty()) {
            null
        } else {
            reviewRound(items, arrangement, uncheckedOnly, now).map { it.id }.toLongArray()
        }
    }
)

/**
 * The clock a round's freshness filter is judged against, fixed for the round.
 *
 * Read once and kept, so "Today" cannot quietly become "yesterday" while the user is part way
 * through a queue, and so the same instant decides the queue and the message shown when that
 * queue comes out empty. Saved with the session; leaving and re-entering asks the clock again.
 */
@Composable
fun rememberRoundClock(sessionKey: Any): Long =
    rememberSaveable(sessionKey) { System.currentTimeMillis() }

/**
 * The snapshot machinery behind every narrowed round, whichever mode decided to narrow one.
 *
 * [round] is asked once — on the first composition that can answer — for the ids this round
 * queues, in the order it walks them. Every later emission of [items] is put through that
 * snapshot instead of being re-narrowed, which is what stops a card vanishing under the user's
 * thumb the moment they check it. Returning null from [round] means "not yet": the items arrive
 * from the database a frame after the screen does, and snapshotting an empty list would queue
 * nothing at all.
 *
 * [narrowed] false hands [items] straight back and never records anything, so a mode that does
 * not narrow costs nothing and, having stored no snapshot, does not start honouring a stale one
 * if the setting behind it is switched off mid-session.
 */
@Composable
fun rememberRoundQueue(
    items: List<ReviewItem>,
    sessionKey: Any,
    narrowed: Boolean,
    round: () -> LongArray?
): List<ReviewItem> {
    var queuedIds by rememberSaveable(sessionKey) { mutableStateOf<LongArray?>(null) }

    val snapshot = when {
        !narrowed -> null
        queuedIds != null -> queuedIds
        else -> round()
    }
    // Recorded after the fact rather than during composition, so the first pass already renders
    // the narrowed queue instead of flashing the whole list and correcting itself.
    SideEffect {
        if (narrowed && queuedIds == null && snapshot != null) queuedIds = snapshot
    }

    val queued = snapshot ?: return items
    return remember(items, queued) { queuedItems(items, queued) }
}

/**
 * [items] narrowed to the round's snapshot, in the snapshot's own order, ignoring ids that are no
 * longer there: a re-sync or a cleanup during a review shortens the queue rather than breaking
 * it. An item checked since the snapshot was taken stays in, which is the whole point.
 *
 * The order comes from [queuedIds] rather than from [items] because the snapshot is the only
 * record of it. Normal Review takes its snapshot in stored order, so nothing moves there; Quick
 * Review takes its snapshot in the order the folder page was showing, and that order would
 * otherwise be lost the moment the database re-emitted.
 */
fun queuedItems(items: List<ReviewItem>, queuedIds: LongArray): List<ReviewItem> {
    val byId = items.associateBy { it.id }
    return queuedIds.toList().mapNotNull { byId[it] }
}

/**
 * Wraps leaving a review so the on-exit webhook fires at most once, whichever way the user goes:
 * the top bar arrow, the system back gesture, or Exit on the completion screen.
 *
 * A queue that already delivered on reaching its end does not deliver again on the way out, and
 * an empty queue never delivers at all — there was no round to report.
 *
 * Both review modes now report when their queue finishes, so this guard is what stops the exit
 * that follows from reporting the same round again. Whether anything is actually sent is the
 * ViewModel's decision; this only says a review was left.
 */
fun leaveReview(
    queue: List<ReviewItem>,
    delivered: State<Boolean>,
    onLeave: (List<ReviewItem>) -> Unit,
    onBack: () -> Unit
): () -> Unit = {
    if (!delivered.value && queue.isNotEmpty()) onLeave(queue)
    onBack()
}

/** Whether this review session has already delivered, kept across rotation like the session. */
@Composable
fun rememberSessionDelivered(sessionKey: Any): MutableState<Boolean> =
    rememberSaveable(sessionKey) { mutableStateOf(false) }

/**
 * The review experience itself, over whatever queue it is handed: the whole list for normal
 * Review, one folder's direct files for Quick Review. There is one implementation of the
 * gestures, the action row and the media renderer, and one underlying item state — both modes
 * write the same persistent per-file decisions through the same ViewModel calls, so completion,
 * actions and the list completion webhook behave identically wherever the swipe came from.
 *
 * [persistedItemId] is the resume point, or null to always start at the first unchecked item.
 * [onPositionChanged] is where a mode decides whether moving is worth remembering: Quick Review
 * passes an empty lambda, which is what leaves the list's own review position untouched.
 * A null [onRestart] hides the restart offer on the completion screen.
 *
 * [onQueueFinished] is called with the queue's item ids the moment the last card is swiped off,
 * for the mode that reports a finished queue of its own. Null for a mode that does not.
 */
@Composable
fun ReviewSession(
    modifier: Modifier,
    viewModel: ListsViewModel,
    items: List<ReviewItem>,
    sessionKey: Any,
    chrome: ReviewChrome,
    persistedItemId: Long?,
    onPositionChanged: (Long) -> Unit,
    onRestart: (() -> Unit)?,
    onQueueFinished: ((List<ReviewItem>) -> Unit)? = null,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var currentItemId by rememberSaveable(sessionKey) { mutableStateOf<Long?>(null) }
    var finished by rememberSaveable(sessionKey) { mutableStateOf(false) }
    // Not saved: reopening Info after a rotation would be answering a question nobody asked.
    var infoOpen by remember { mutableStateOf(false) }
    // Whether the bars are up, toggled by a tap on the media. Belongs to the session and not to
    // the card: a user who put the chrome away to look at photographs full-bleed meant it for the
    // rest of the queue, not for one item, so swiping does not bring it back and does not take it
    // away. Saved with the session so a rotation does not either.
    var chromeVisible by rememberSaveable(sessionKey) { mutableStateOf(true) }

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
            completed = chrome.progress.completed,
            total = chrome.progress.total,
            roundSize = items.size,
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
    // The status and navigation bars follow Listea's own, so hiding the chrome really does give
    // the whole display to the media.
    MediaSystemBars(chromeVisible)

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
        chromeVisible = chromeVisible,
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
                    if (index == items.lastIndex) {
                        finished = true
                        // Announced after the check above, so the delivery it may lead to sees
                        // the item that was just swiped as checked.
                        onQueueFinished?.invoke(items)
                    } else {
                        goTo(index + 1)
                    }
                },
                onSwipeBack = { goTo(index - 1) },
                modifier = Modifier.fillMaxSize(),
                // The one control that is not in a bar, because it is the control *for* the bars.
                onTap = { chromeVisible = !chromeVisible }
            ) { zoom ->
                ItemPreview(
                    item = item,
                    player = player,
                    imageLoader = imageLoader,
                    settings = settings,
                    zoom = zoom,
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
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, settings, chrome.listTitle),
            onDismiss = { infoOpen = false }
        )
    }
}

/**
 * Everything the media surface stopped saying about the item in front of the user.
 *
 * The queue-side facts are already in memory on the [ReviewItem], so [reviewItemInfo] costs
 * nothing. Type, size and modified time are not stored anywhere and are read once, here, while
 * the sheet is open — never per card and never while swiping.
 *
 * Shared with [ListItemViewerScreen], which shows the same items without being able to change
 * them. Nothing in here writes to the *item*, so a read-only screen can say exactly as much about
 * one as Review can — and can offer the same Save, which copies the file into Listea's gallery
 * album without touching the item, its completion or its actions.
 *
 * Save is offered only when there is a file to copy: a manual item has no source at all, and one
 * whose source has gone missing has a URI that no longer resolves. Both say so in a row above
 * instead of showing a button that could only fail.
 */
@Composable
fun ReviewItemInfoSheet(info: ReviewItemInfo, onDismiss: () -> Unit) {
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
        },
        footer = info.sourceUri
            ?.takeIf { !info.sourceMissing }
            ?.let { uri -> { MediaFileActions(sourceUri = uri, name = info.title) } }
    )
}

/**
 * Resume rules: a still-valid persisted item wins, even if it is already checked. Otherwise start
 * at the first unchecked item, and if everything is checked, at the very first item. Passing a
 * null [persisted] is how Quick Review always starts at the first unchecked item.
 */
private fun resumeItemId(persisted: Long?, items: List<ReviewItem>): Long {
    if (persisted != null && items.any { it.id == persisted }) return persisted
    return (items.firstOrNull { !it.decisions.isCompleted } ?: items.first()).id
}

/**
 * Loading, and the queues that turn out to have nothing in them.
 *
 * Same thin bar as the media screens so entering a review never jumps between two layouts, minus
 * the position, because there is no queue to be anywhere in yet.
 *
 * It carries its own system-bar inset. The shell stops insetting nested screens so that a media
 * screen can run edge to edge, and this is ordinary content that still has to sit below the
 * status bar.
 */
@Composable
fun ReviewFrame(
    modifier: Modifier,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
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

/**
 * What a finished round leaves on screen: what it was, how far the whole thing has got, and — only
 * when they differ — how much of it this round actually covered.
 *
 * The two numbers are kept apart on purpose. "50 / 100 processed" is the list, and it is the one
 * that decides whether the list is done. "This round covered 50 items" is the queue, and it is
 * there so a user who forgot a filter was on can see why finishing did not finish anything.
 * Running them together as one heading is what made them look like a contradiction.
 */
@Composable
private fun ReviewFinished(
    modifier: Modifier,
    headline: String,
    title: String,
    completed: Int,
    total: Int,
    roundSize: Int,
    exitLabel: String,
    onBack: () -> Unit,
    onRestart: (() -> Unit)?
) {
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
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
        if (roundSize < total) {
            Text(
                "This round covered " + countLabel(roundSize, "item"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
 *
 * The two placeholders take the zoom transform as well as the pictures do. They are not media and
 * there is nothing in them worth a closer look, but leaving them out would mean a pinch on one
 * silently changed a scale that nothing then honoured - and a scale above one is what decides
 * whether the next drag pages or pans.
 *
 * Shared with [ListItemViewerScreen] so that an item looks the same wherever it is opened from.
 * It renders and nothing else — no completion, no actions, no ViewModel — which is what lets a
 * read-only screen use it unchanged.
 */
@Composable
fun ItemPreview(
    item: ReviewItem,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    settings: AppSettings,
    zoom: MediaZoomState,
    modifier: Modifier
) {
    val uri = item.sourceUri
    if (uri == null) {
        LaunchedEffect(zoom) { zoom.contentAspect = null }
        PreviewPlaceholder(modifier.mediaZoom(zoom), "Manual item", item.title)
        return
    }
    if (item.sourceMissing) {
        LaunchedEffect(zoom) { zoom.contentAspect = null }
        PreviewPlaceholder(modifier.mediaZoom(zoom), "Source missing", item.title)
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
        zoom = zoom
    )
}
