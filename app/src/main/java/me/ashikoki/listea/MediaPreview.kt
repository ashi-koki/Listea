package me.ashikoki.listea

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
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
import kotlinx.coroutines.withContext

private enum class PreviewKind { Image, Video, Unsupported }

/**
 * Renders whatever a content URI points at: still images, animated GIF/WebP, video, and a
 * placeholder for everything else.
 *
 * The one renderer in the app. Review shows list items through it and the Folder browser shows
 * plain files through it, so a format that plays in one place plays in the other. It knows only
 * about a URI and a name — nothing about lists, completion or actions — which is what lets the
 * Folder browser open a file without any business state being involved.
 */
@Composable
fun MediaPreview(
    uri: String,
    name: String,
    player: ExoPlayer,
    imageLoader: ImageLoader,
    modifier: Modifier,
    unsupportedHeadline: String = "Preview unavailable",
    videoAutoplay: Boolean = true,
    videoStartMuted: Boolean = false,
    isFullscreen: Boolean = false,
    onFullscreenChange: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val kind by produceState(PreviewKind.Unsupported, uri, name) {
        value = withContext(Dispatchers.IO) { previewKindOf(context, uri, name) }
    }

    when (kind) {
        PreviewKind.Image -> ImagePreview(uri, name, imageLoader, modifier, unsupportedHeadline)
        PreviewKind.Video -> VideoPreview(
            player = player,
            uri = uri,
            modifier = modifier,
            autoplay = videoAutoplay,
            startMuted = videoStartMuted,
            isFullscreen = isFullscreen,
            onFullscreenChange = onFullscreenChange
        )
        PreviewKind.Unsupported -> PreviewPlaceholder(modifier, unsupportedHeadline, name)
    }
}

@Composable
private fun ImagePreview(
    uri: String,
    title: String,
    imageLoader: ImageLoader,
    modifier: Modifier,
    failureHeadline: String
) {
    var failed by remember(uri) { mutableStateOf(false) }

    if (failed) {
        PreviewPlaceholder(modifier, failureHeadline, title)
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

/**
 * Media3 playback, with Media3's own controls and nothing of Listea's laid over them.
 *
 * The controls start hidden and appear on a touch. That is what `controllerAutoShow = false`
 * buys: PlayerView otherwise shows them the moment the player becomes ready or pauses, which
 * meant every video opened behind a bar of transport buttons whether or not it was playing.
 * Touch shows them, touch again or the usual timeout hides them.
 *
 * The fullscreen button exists only when a caller has somewhere for it to go — PlayerView shows
 * it if and only if a listener is set — so a screen that cannot go fullscreen does not display a
 * button that would do nothing.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(
    player: ExoPlayer,
    uri: String,
    modifier: Modifier,
    autoplay: Boolean,
    startMuted: Boolean,
    isFullscreen: Boolean,
    onFullscreenChange: ((Boolean) -> Unit)?
) {
    // Bind on enter, stop on leave, so a swiped-away or closed video never keeps playing.
    DisposableEffect(uri, autoplay, startMuted) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.volume = if (startMuted) 0f else 1f
        player.playWhenReady = autoplay
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
                // Only a touch brings the transport controls up.
                controllerAutoShow = false
                hideController()
            }
        },
        update = { view ->
            if (onFullscreenChange == null) {
                view.setFullscreenButtonClickListener(null)
            } else {
                view.setFullscreenButtonClickListener { onFullscreenChange(it) }
                // Keeps the button's icon honest when the state is changed from outside it —
                // leaving fullscreen with Back, or a swipe onto the next item.
                view.setFullscreenButtonState(isFullscreen)
            }
        },
        modifier = modifier
    )
}

@Composable
fun PreviewPlaceholder(modifier: Modifier, headline: String, name: String) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(ListeaDimens.CompactGap))
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
fun buildImageLoader(context: Context): ImageLoader =
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
