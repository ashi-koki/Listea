package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plain file viewing from the Folder browser, with gallery-style paging through the files sitting
 * beside it.
 *
 * Deliberately has nothing to do with lists. It opens whatever the user tapped — covered by a
 * list or not, checked or not, part of a snapshot or not — and offers no checkbox, no actions and
 * no completion. Swiping moves between direct siblings in the same folder and changes nothing at
 * all: viewing a file and acting on a list item stay separate ideas, which is why this screen
 * takes [FolderEntry]s and never a ListItem.
 *
 * Rendering is the shared [MediaPreview] and paging is the shared [SwipeCard], so images, GIF and
 * video behave as they do in Review, and the swipe feels the same — minus everything Review does
 * to the item on its way past.
 */
@Composable
fun FileViewerScreen(
    modifier: Modifier,
    files: List<FolderEntry>,
    initial: FolderEntry,
    detailOf: (FolderEntry) -> String,
    settings: AppSettings,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    var currentUri by rememberSaveable(initial.uri) { mutableStateOf(initial.uri.toString()) }

    // A background refresh can drop a file from the folder while it is open. Rather than close on
    // the user, fall back to showing the one they actually tapped, without siblings to page to.
    val index = files.indexOfFirst { it.uri.toString() == currentUri }
    val entry = files.getOrNull(index) ?: initial

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Folder") }
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (index >= 0) {
                Text(
                    "${index + 1} / ${files.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            detailOf(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HorizontalDivider()

        SwipeCard(
            key = currentUri,
            canSwipeForward = index >= 0 && index < files.lastIndex,
            canSwipeBack = index > 0,
            onSwipeForward = { currentUri = files[index + 1].uri.toString() },
            onSwipeBack = { currentUri = files[index - 1].uri.toString() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            MediaPreview(
                uri = entry.uri.toString(),
                name = entry.name,
                player = player,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                unsupportedHeadline = "Preview not supported",
                videoAutoplay = settings.videoAutoplay,
                videoStartMuted = settings.videoStartMuted
            )
        }

        HorizontalDivider()
        Text(
            "Swipe left or right to move between files in this folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
