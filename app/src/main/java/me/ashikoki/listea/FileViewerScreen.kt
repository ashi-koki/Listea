package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    folderPath: String,
    settings: AppSettings,
    onClose: () -> Unit
) {

    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val imageLoader = remember(context) { buildImageLoader(context) }

    var currentUri by rememberSaveable(initial.uri) { mutableStateOf(initial.uri.toString()) }
    // Not saved: rotating should return to the file, not to a sheet about it.
    var infoOpen by remember { mutableStateOf(false) }
    // Toggled by a tap on the media, and kept across paging: this is the gallery behaviour, where
    // putting the bars away is a decision about the whole browse and not about one file.
    var chromeVisible by rememberSaveable { mutableStateOf(true) }

    // A background refresh can drop a file from the folder while it is open. Rather than close on
    // the user, fall back to showing the one they actually tapped, without siblings to page to.
    val index = files.indexOfFirst { it.uri.toString() == currentUri }
    val entry = files.getOrNull(index) ?: initial

    // The system bars come and go with Listea's own, so a hidden chrome means a full-bleed file.
    MediaSystemBars(chromeVisible)
    // Back closes the viewer whatever the chrome is doing. Hiding it is not a mode to escape from
    // - a tap anywhere brings it straight back - so making Back mean "show the bars again" would
    // only ever cost a second press on the way out.
    BackHandler { onClose() }

    MediaShell(
        modifier = modifier,
        title = entry.name,
        position = if (index >= 0) "${index + 1} / ${files.size}" else null,
        onBack = onClose,
        chromeVisible = chromeVisible,
        media = {
            SwipeCard(
                key = currentUri,
                canSwipeForward = index >= 0 && index < files.lastIndex,
                canSwipeBack = index > 0,
                onSwipeForward = { currentUri = files[index + 1].uri.toString() },
                onSwipeBack = { currentUri = files[index - 1].uri.toString() },
                modifier = Modifier.fillMaxSize(),
                onTap = { chromeVisible = !chromeVisible }
            ) { zoom ->
                MediaPreview(
                    uri = entry.uri.toString(),
                    name = entry.name,
                    player = player,
                    imageLoader = imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    videoAutoplay = settings.videoAutoplay,
                    videoStartMuted = settings.videoStartMuted,
                    zoom = zoom
                )
            }
        },
        // A gallery has no business actions: no completion, no favourite, no custom slots.
        // The one control is the one that answers "what am I looking at?".
        controls = { MediaInfoBar("File info") { infoOpen = true } }
    )

    if (infoOpen) {
        FileInfoSheet(entry, folderPath) { infoOpen = false }
    }
}

/**
 * What the top bar had to ellipsize, plus where the file sits and what it is.
 *
 * Size and modified time are already on the [FolderEntry] the folder listing produced, so this
 * shows them immediately and lets the one provider lookup fill in the MIME type when it lands.
 * Nothing here can change anything about the *list* state — Save is the one exception, and it
 * changes nothing about the file either: it copies it into Listea's gallery album and leaves the
 * original where the folder browser found it.
 */
@Composable
private fun FileInfoSheet(entry: FolderEntry, folderPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = entry.uri.toString()
    val facts by produceState(MediaFacts(), uri) { value = readMediaFacts(context, uri) }

    ItemInfoSheet(
        title = entry.name,
        onDismiss = onDismiss,
        rows = buildList {
            add(InfoField("Folder", folderPath.ifEmpty { "Selected folder" }))
            addAll(
                mediaFactRows(
                    facts = facts,
                    name = entry.name,
                    fallbackSize = entry.sizeBytes,
                    fallbackModified = entry.lastModified
                )
            )
        },
        footer = { MediaFileActions(sourceUri = uri, name = entry.name) }
    )
}
