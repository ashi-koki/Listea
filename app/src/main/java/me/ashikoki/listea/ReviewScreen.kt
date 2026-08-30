package me.ashikoki.listea

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity

/** How far the card must travel before a swipe counts as navigation. */
private val SWIPE_THRESHOLD = 96.dp

/** How far a blocked swipe (backwards at the first item) is allowed to rubber band. */
private const val RESIST_LIMIT = 80f

private enum class PreviewKind { Image, Video, Unsupported }

/**
 * Sequential one-item-at-a-time review of a list, in its stored order.
 *
 * Swipe left  = mark the current item completed, then advance.
 * Swipe right = step back only; it never changes any completion state.
 *
 * Position is tracked by item id, so re-sync, cleanup and deletions cannot leave it pointing at
 * the wrong item.
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

    var currentItemId by rememberSaveable(listId) { mutableStateOf<Long?>(null) }
    var finished by rememberSaveable(listId) { mutableStateOf(false) }

    BackHandler { onBack() }

    // One player for the whole screen rather than one per card, so nothing leaks between items.
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val imageLoader = remember(context) { buildImageLoader(context) }

    val current = detail
    if (current == null) {
        ReviewFrame(modifier, title = "", onBack = onBack) { CircularProgressIndicator() }
        return
    }

    val items = current.items
    if (items.isEmpty()) {
        ReviewFrame(modifier, title = current.list.title, onBack = onBack) {
            Text("This list has no items to review.")
        }
        return
    }

    // Resolve the resume position, and repair it if the item it pointed at has gone.
    LaunchedEffect(items, currentItemId) {
        if (items.none { it.id == currentItemId }) {
            currentItemId = resumeItemId(current.list.reviewCurrentItemId, items)
        }
    }

    val index = items.indexOfFirst { it.id == currentItemId }
    if (index < 0) {
        ReviewFrame(modifier, title = current.list.title, onBack = onBack) {
            CircularProgressIndicator()
        }
        return
    }

    if (finished) {
        ReviewFinished(
            modifier = modifier,
            title = current.list.title,
            completed = current.completedCount,
            total = items.size,
            onBack = onBack,
            onRestart = {
                finished = false
                currentItemId = items.first().id
                viewModel.setReviewPosition(listId, items.first().id)
            }
        )
        return
    }

    val item = items[index]
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(item.id) { offsetX.snapTo(0f) }

    fun goTo(newIndex: Int) {
        val id = items[newIndex].id
        currentItemId = id
        viewModel.setReviewPosition(listId, id)
    }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ List") }
            Text(
                current.list.title,
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
            progressLabel(current.completedCount, items.size, current.isComplete),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                SWIPE_THRESHOLD.toPx()
            }
            val atFirst = index == 0
            val atLast = index == items.lastIndex

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = offsetX.value }
                    .pointerInput(item.id, atFirst, atLast) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, delta ->
                                scope.launch {
                                    val next = offsetX.value + delta
                                    // Backwards from the first item resists instead of navigating.
                                    offsetX.snapTo(
                                        if (atFirst && next > RESIST_LIMIT) RESIST_LIMIT else next
                                    )
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetX.animateTo(0f, tween(150)) }
                            },
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        offsetX.value < -thresholdPx -> {
                                            offsetX.animateTo(-widthPx, tween(180))
                                            // The existing completion path, so a list that becomes
                                            // complete here fires the webhook exactly as a manual
                                            // checkbox tick would.
                                            viewModel.setItemCompleted(item, true)
                                            if (atLast) finished = true else goTo(index + 1)
                                        }

                                        offsetX.value > thresholdPx && !atFirst -> {
                                            offsetX.animateTo(widthPx, tween(180))
                                            goTo(index - 1)
                                        }

                                        else -> offsetX.animateTo(0f, tween(180))
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                ItemPreview(
                    item = item,
                    player = player,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        HorizontalDivider()
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
                label = { Text(action.label) }
            )
        }
    }
}

/**
 * Resume rules: a still-valid persisted item wins, even if it is already checked. Otherwise start
 * at the first unchecked item, and if everything is checked, at the very first item.
 */
private fun resumeItemId(persisted: Long?, items: List<ListItemEntity>): Long {
    if (persisted != null && items.any { it.id == persisted }) return persisted
    return (items.firstOrNull { !it.isCompleted } ?: items.first()).id
}

@Composable
private fun ReviewFrame(
    modifier: Modifier,
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ List") }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ReviewFinished(
    modifier: Modifier,
    title: String,
    completed: Int,
    total: Int,
    onBack: () -> Unit,
    onRestart: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Review complete", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            "$completed / $total processed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("Back to list") }
            OutlinedButton(onClick = onRestart) { Text("Review again") }
        }
    }
}

/**
 * Renders whatever the item points at. Every item gets a card: nothing is filtered out of the
 * queue, so anything unrenderable falls through to a placeholder and stays swipeable.
 */
@Composable
private fun ItemPreview(
    item: ListItemEntity,
    player: ExoPlayer,
    imageLoader: ImageLoader,
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

    val context = LocalContext.current
    val kind by produceState(PreviewKind.Unsupported, uri, item.title) {
        value = withContext(Dispatchers.IO) { previewKindOf(context, uri, item.title) }
    }

    when (kind) {
        PreviewKind.Image -> ImagePreview(uri, item.title, imageLoader, modifier)
        PreviewKind.Video -> VideoPreview(player, uri, modifier)
        PreviewKind.Unsupported -> PreviewPlaceholder(modifier, "Preview unavailable", item.title)
    }
}

@Composable
private fun ImagePreview(
    uri: String,
    title: String,
    imageLoader: ImageLoader,
    modifier: Modifier
) {
    var failed by remember(uri) { mutableStateOf(false) }

    if (failed) {
        PreviewPlaceholder(modifier, "Preview unavailable", title)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build(),
        imageLoader = imageLoader,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true }
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(player: ExoPlayer, uri: String, modifier: Modifier) {
    // Bind on enter, stop on leave, so a swiped-away video never keeps playing.
    DisposableEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.pause()
            player.clearMediaItems()
        }
    }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier
    )
}

@Composable
private fun PreviewPlaceholder(modifier: Modifier, headline: String, name: String) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Animated GIF/WebP support comes from the platform decoder; nothing is decoded by hand. */
private fun buildImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

/**
 * Asks the content provider what the file is, falling back to the extension. Blocking: call on an
 * IO dispatcher. A stale or revoked URI just yields Unsupported, which renders a placeholder.
 */
private fun previewKindOf(context: Context, uri: String, name: String): PreviewKind {
    val mime = runCatching { context.contentResolver.getType(uri.toUri()) }.getOrNull()
        ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    return when {
        mime == null -> PreviewKind.Unsupported
        mime.startsWith("image/") -> PreviewKind.Image
        mime.startsWith("video/") -> PreviewKind.Video
        else -> PreviewKind.Unsupported
    }
}
