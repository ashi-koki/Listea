package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import me.ashikoki.listea.data.ReviewItem

/**
 * Looking at what a list item actually is, opened by tapping it on the list page.
 *
 * Tapping an item used to open a rename box. Renaming a folder-backed item was a trap: the title
 * is how the item is recognised on the next source scan and what a webhook payload carries, so a
 * rename either came back undone or quietly detached the row from the file it stood for. The
 * gesture is now the one people expect from a list of pictures — it opens the picture.
 *
 * Deliberately read-only, and that is the whole distinction between this and Review. It has no
 * checkbox and no action chips, so a look cannot become a decision by accident. Nothing here
 * touches the ViewModel at all: there is no call it could make.
 *
 * What it *is* identical to is the folder File Viewer — same shell, same swipe, same pinch, same
 * lone Info button — because they are the same activity done from two directions. The difference
 * is only in what is being paged through: this walks the list's own items in the list's own
 * order, so a swipe reaches the next item on the page rather than the next file in a folder, and
 * an item with no file behind it still gets its place in the sequence rather than being skipped.
 */
@Composable
fun ListItemViewerScreen(
    modifier: Modifier,
    items: List<ReviewItem>,
    initialItemId: Long,
    listTitle: String,
    settings: AppSettings,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    // Keyed on what was opened, so tapping a different item starts there rather than resuming
    // wherever the last look happened to end.
    var currentItemId by rememberSaveable(initialItemId) { mutableStateOf(initialItemId) }
    // Not saved: rotating should return to the item, not to a sheet about it.
    var infoOpen by remember { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    val index = items.indexOfFirst { it.id == currentItemId }

    // A resync or a cleanup can take the item away while it is open. Closing is better than
    // sliding to a neighbour, which would leave the user looking at something they never asked
    // for and had no way to tell they were now looking at.
    LaunchedEffect(items, currentItemId) {
        if (items.none { it.id == currentItemId }) onClose()
    }
    val item = items.getOrNull(index) ?: return

    MediaSystemBars(chromeVisible)
    BackHandler { onClose() }

    MediaShell(
        modifier = modifier,
        title = item.title,
        // Where in the list this is, which is the same number the page it was opened from shows.
        position = "${index + 1} / ${items.size}",
        onBack = onClose,
        chromeVisible = chromeVisible,
        media = {
            SwipeCard(
                key = item.id,
                // Both ends simply stop. There is no queue being worked through and nothing to
                // complete, so running off the end is not an event — it is just the end.
                canSwipeForward = index < items.lastIndex,
                canSwipeBack = index > 0,
                onSwipeForward = { currentItemId = items[index + 1].id },
                onSwipeBack = { currentItemId = items[index - 1].id },
                modifier = Modifier.fillMaxSize(),
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
        controls = { MediaInfoBar("Item info") { infoOpen = true } }
    )

    if (infoOpen) {
        // The same sheet Review shows, including the item's completion and its actions. Reading
        // them here is not a way to set them: the sheet has never had a control on it.
        ReviewItemInfoSheet(
            info = reviewItemInfo(item, settings, listTitle),
            onDismiss = { infoOpen = false }
        )
    }
}
