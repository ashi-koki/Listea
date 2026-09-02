package me.ashikoki.listea

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
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
    zoom: MediaZoomState? = null
) {
    val context = LocalContext.current
    val kind by produceState(PreviewKind.Unsupported, uri, name) {
        value = withContext(Dispatchers.IO) { previewKindOf(context, uri, name) }
    }

    when (kind) {
        PreviewKind.Image ->
            ImagePreview(uri, name, imageLoader, modifier, unsupportedHeadline, zoom)

        PreviewKind.Video -> VideoPreview(
            player = player,
            uri = uri,
            modifier = modifier,
            autoplay = videoAutoplay,
            startMuted = videoStartMuted,
            zoom = zoom
        )

        PreviewKind.Unsupported -> {
            // A placeholder has no shape of its own to report: it fills the card, and saying so
            // is what stops the pan limits being sized from a previous card's photograph.
            LaunchedEffect(uri, zoom) { zoom?.contentAspect = null }
            PreviewPlaceholder(modifier.mediaZoom(zoom), unsupportedHeadline, name)
        }
    }
}

/**
 * A still or animated image, scaled to fit and pinch-zoomable on top of that fit.
 *
 * The load state is where the picture's own proportions become known, so it is also where they
 * are reported: [ContentScale.Fit] letterboxes it inside the card, and how much of the card is
 * letterbox rather than picture is exactly what decides how far a zoomed pan may go. Reported as
 * unknown again while loading and on failure, so a card never inherits the shape of the one
 * before it.
 */
@Composable
private fun ImagePreview(
    uri: String,
    title: String,
    imageLoader: ImageLoader,
    modifier: Modifier,
    failureHeadline: String,
    zoom: MediaZoomState?
) {
    var failed by remember(uri) { mutableStateOf(false) }

    if (failed) {
        PreviewPlaceholder(modifier.mediaZoom(zoom), failureHeadline, title)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(uri).crossfade(true).build(),
        imageLoader = imageLoader,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = modifier.mediaZoom(zoom),
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) failed = true
            zoom?.contentAspect = (state as? AsyncImagePainter.State.Success)
                ?.painter?.intrinsicSize?.aspectRatioOrNull()
        }
    )
}

/**
 * Media3 playback, split into the picture and the transport controls, because Listea's own chrome
 * now sits between them.
 *
 * The [PlayerView] renders nothing but the video: `useController = false` takes its built-in
 * controller away *and* makes it non-clickable, which is what lets a tap or a drag anywhere over
 * a video reach the swipe card underneath. Paging and toggling the chrome therefore work the same
 * over a video as over a photograph, which they did not while PlayerView was eating every touch
 * to run its own controller.
 *
 * The controls come back as a standalone [PlayerControlView] laid over the picture, and only when
 * Listea's chrome is up: one tap raises the top bar, the action bar and the transport controls
 * together, and one tap takes all three away. It is inset by exactly what the chrome measured
 * itself to be ([LocalMediaChrome]), so the settings gear and the scrubber land in the band
 * between the two bars instead of underneath the action row - a fixed guess at the bar height
 * would be wrong the moment an action label wrapped onto a second line.
 *
 * `showTimeoutMs = 0` hands visibility entirely to the chrome: no timeout, no auto-hide, nothing
 * that could leave the video's controls and Listea's disagreeing about whether the screen is
 * showing controls at all. There is no fullscreen button, because the picture is already the
 * whole screen and a tap is what hides everything over it - and no previous/next, because the
 * player is only ever given the one item.
 *
 * Zooming reaches the picture and stops there. The transform is on the [PlayerView] alone, not on
 * the box holding both it and the controls: a pinched-in video that dragged its own pause button
 * off the side of the screen would be unpausable.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(
    player: ExoPlayer,
    uri: String,
    modifier: Modifier,
    autoplay: Boolean,
    startMuted: Boolean,
    zoom: MediaZoomState?
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

    // A video's shape is not known until it has been read far enough to say so, and it is the
    // same fact an image reports from its load state: how much of the card is picture rather than
    // letterbox, and therefore how far a zoomed pan is allowed to travel.
    DisposableEffect(player, uri, zoom) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                zoom?.contentAspect = videoSize.aspectRatioOrNull()
            }
        }
        zoom?.contentAspect = player.videoSize.aspectRatioOrNull()
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            zoom?.contentAspect = null
        }
    }

    val chrome = LocalMediaChrome.current
    Box(modifier) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .mediaZoom(zoom)
        )

        if (chrome.visible) {
            AndroidView(
                factory = { context ->
                    PlayerControlView(context).apply {
                        this.player = player
                        showTimeoutMs = 0
                        setShowPreviousButton(false)
                        setShowNextButton(false)
                        show()
                    }
                },
                // The player outlives any one card; re-point the controls at it and keep them up
                // if the view is reused across a recomposition.
                update = { view ->
                    view.player = player
                    view.show()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = chrome.top, bottom = chrome.bottom)
            )
        }
    }
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

/**
 * A decoded video's shape as the screen sees it, or null before there is one.
 *
 * Corrected by the pixel aspect ratio, so anamorphic material reports the shape it is displayed
 * at rather than the shape it is stored at - which is the one that matters, because PlayerView
 * letterboxes it to the former.
 */
private fun VideoSize.aspectRatioOrNull(): Float? {
    if (width <= 0 || height <= 0) return null
    val pixelRatio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    return width * pixelRatio / height
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
