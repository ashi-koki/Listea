package me.ashikoki.listea

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ReviewItem
import me.ashikoki.listea.ui.theme.ListeaMediaChromeTheme

/**
 * The V4.3 media surface, shared by Full Review, Quick Review and the folder File Viewer.
 *
 * One presentation for all three, and two layers rather than one column: the media fills the
 * screen, and the bars are drawn *over* it. Nothing about the chrome changes how large the media
 * is, which is the whole point - a 16:9 photograph in landscape used to be squeezed into whatever
 * a fixed top bar and a fixed action row left behind, and came out smaller than the same
 * photograph in portrait.
 *
 * There is no subtitle slot and no hint slot - not "unused by default" but absent. What used to
 * live there is in [ItemInfoSheet], opened on request.
 */

/**
 * How much of the media surface the chrome is covering, published to whatever is inside it.
 *
 * Only the video player reads this. An image does not care that a bar is sitting on top of it -
 * it is letterboxed and centred either way - but a video brings its own controls, and those have
 * to land somewhere the action bar is not. See [LocalMediaChrome].
 */
@Immutable
data class MediaChromeInsets(
    val visible: Boolean = false,
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp
)

/**
 * The chrome's footprint, ambient to everything [MediaShell] draws media into.
 *
 * A local rather than a parameter because the only reader is four call hops down - shell, swipe
 * card, item preview, renderer - through two screens that have no interest in the value and would
 * each have to carry it anyway. Anything composed outside a [MediaShell] sees an empty, invisible
 * chrome, which is the right answer for a thumbnail or a preview.
 */
val LocalMediaChrome = compositionLocalOf { MediaChromeInsets() }

/**
 * The frame every media screen shares: media underneath, chrome on top of it.
 *
 * The media slot is given the whole box in both orientations, always, and is composed exactly
 * once - [chromeVisible] adds and removes the two bars *beside* it in the stack, never around it,
 * so toggling them cannot remeasure the media, restart a decode or interrupt playback.
 *
 * The background is black rather than a theme surface: ContentScale.Fit letterboxes almost
 * everything, and a photograph reads better against black in a light theme than a light theme
 * reads against a photograph. The bars are a scrim over that, with their contents forced to the
 * dark scheme by [ListeaMediaChromeTheme] so they stay legible whatever is behind them.
 *
 * Toggling [chromeVisible] is not this composable's business. The gesture that does it belongs to
 * the media - a tap has to miss the bars to count - so the state is hoisted to the screen and
 * driven from the swipe card, which is also what keeps it independent of which item is showing.
 *
 * Each bar carries its own system-bar inset. The media deliberately carries none: it runs edge to
 * edge, under the status and navigation bars, exactly as a gallery does.
 */
@Composable
fun MediaShell(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    position: String? = null,
    chromeVisible: Boolean = true,
    controls: @Composable (() -> Unit)? = null,
    media: @Composable () -> Unit
) {
    // What the bars actually measured, so a video's own controls can sit between them rather than
    // under them. Reported as zero while the chrome is down, which is when the bar that measured
    // the height is not on screen to be avoided.
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CompositionLocalProvider(
            LocalMediaChrome provides MediaChromeInsets(
                visible = chromeVisible,
                top = if (chromeVisible) topBarHeight else 0.dp,
                bottom = if (chromeVisible) bottomBarHeight else 0.dp
            )
        ) {
            media()
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            MediaChromeBar(
                insets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                onHeightChange = { topBarHeight = it }
            ) {
                MediaTopBar(title = title, position = position, onBack = onBack)
            }
        }

        controls?.let { bar ->
            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                MediaChromeBar(
                    insets = WindowInsets.safeDrawing
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    onHeightChange = { bottomBarHeight = it }
                ) {
                    bar()
                }
            }
        }
    }
}

/**
 * One scrimmed bar of chrome, laid over the media and reporting how much of it it hides.
 *
 * The scrim sits outside the inset padding, so it runs under the status or navigation bar rather
 * than stopping short of it and leaving the system icons on the bare photograph. The reported
 * height is the whole node, inset included, because that is the part of the media the caller
 * cannot use.
 *
 * The scrim is [MediaChromeScrim], which is PlayerControlView's own value; matching it is what
 * makes a video's controls and Listea's bars read as one dimmed layer instead of three.
 */
@Composable
private fun MediaChromeBar(
    insets: WindowInsets,
    onHeightChange: (Dp) -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) }
            .background(MediaChromeScrim)
            .windowInsetsPadding(insets)
    ) {
        ListeaMediaChromeTheme { content() }
    }
}

/**
 * The dim behind anything Listea draws over media, shared so a bar and the video's own volume
 * control cannot end up two different shades of black. 60% is PlayerControlView's own scrim value.
 */
val MediaChromeScrim = Color.Black.copy(alpha = 0.6f)

/**
 * Ties the system bars to Listea's own: both sets are up together, or neither is.
 *
 * A media screen owns the whole display while it is open, so it owns the status and navigation
 * bars too. Hiding the chrome hides them as well - that is what "the picture is as large as the
 * screen" has to mean - and the hide is the transient-by-swipe kind, so the bars are always one
 * gesture away and nothing here can strand the user. A tap on the media brings everything back
 * at once.
 *
 * The bar icons are forced light for as long as the screen is up, because what is behind them is
 * either black or a photograph, never the app's surface. [DisposableEffect] restores the
 * appearance and the bars themselves however the screen is left - back, a swipe, or the activity
 * going away.
 */
@Composable
fun MediaSystemBars(chromeVisible: Boolean) {
    val view = LocalView.current
    val controller = remember(view) {
        view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
    }

    DisposableEffect(controller) {
        val active = controller ?: return@DisposableEffect onDispose { }
        val hadLightStatusBars = active.isAppearanceLightStatusBars
        val hadLightNavigationBars = active.isAppearanceLightNavigationBars
        active.isAppearanceLightStatusBars = false
        active.isAppearanceLightNavigationBars = false
        active.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            active.show(WindowInsetsCompat.Type.systemBars())
            active.isAppearanceLightStatusBars = hadLightStatusBars
            active.isAppearanceLightNavigationBars = hadLightNavigationBars
        }
    }

    // Keyed on the state rather than run on every recomposition: while the chrome is down the
    // user can still swipe the system bars back transiently, and re-issuing hide() from an
    // unrelated recomposition would take them away again mid-glance.
    LaunchedEffect(controller, chromeVisible) {
        val active = controller ?: return@LaunchedEffect
        if (chromeVisible) {
            active.show(WindowInsetsCompat.Type.systemBars())
        } else {
            active.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Back, what you are looking at, and where you are in the queue. Nothing else.
 *
 * The title carries the weight, so a long filename ellipsizes into the space left over instead of
 * pushing the position off the right edge, and the bar is one line tall whatever it is given.
 * The back affordance is the icon alone: where it goes is obvious from how you got here, and
 * "Back to List" cost a third of the bar to say so.
 */
@Composable
private fun MediaTopBar(title: String, position: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = ListeaDimens.PagePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        position?.let {
            Spacer(Modifier.width(ListeaDimens.RowGap))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * The one review action row, used by Full Review and Quick Review alike.
 *
 * Every control edits the decisions the [ReviewItem] carries, through the caller's ViewModel
 * calls, which is what keeps a decision reached in Quick Review indistinguishable from one
 * reached in Review — including the webhook it may fire. Nothing here navigates.
 *
 * It sits outside the swipe detector, as a sibling of the media rather than a child of it, so a
 * tap or a drag that starts on a chip can never be read as a swipe.
 *
 * Completion anchors the leading edge and Info the trailing one; the three tagging controls sit
 * centred between them. Info is apart because it opens a panel rather than toggling anything and
 * should not be mistaken for a fourth thing that can be switched on; completion is apart because
 * it is the one control the swipe gesture also performs, and because a checkbox is a different
 * kind of thing from a chip.
 *
 * Three zones, and the two outer ones are both a 48dp touch target, so the middle is centred on
 * the bar without needing a spacer to balance it. Anchoring completion is what buys the middle
 * zone the width to stay on one line: it was previously in the group, and a checkbox plus its gap
 * was the ~56dp that pushed ordinary labels onto a second row while an empty mirror spacer sat
 * doing nothing on the left.
 *
 * Both outer controls are unweighted, so they measure at full size before the middle is given
 * what is left — neither can be squeezed out however long a custom label is.
 */
@Composable
fun ReviewActionBar(
    item: ReviewItem,
    settings: AppSettings,
    onToggleCompleted: (Boolean) -> Unit,
    onToggleAction: (ItemAction, Boolean) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ListeaDimens.RowGap, vertical = ListeaDimens.CompactGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.decisions.isCompleted,
            onCheckedChange = onToggleCompleted,
            modifier = Modifier.semantics {
                contentDescription =
                    if (item.decisions.isCompleted) "Checked" else "Not checked"
            }
        )

        // Wraps rather than clipping: ordinary labels are one centred line, and a pair of
        // unusually long custom names costs a second line instead of an unreachable control.
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(
                ListeaDimens.RowGap,
                Alignment.CenterHorizontally
            ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed order, so the row never reshuffles as actions are toggled.
            ItemAction.entries.forEach { action ->
                val isSet = action.isSetOn(item.decisions)
                if (action == ItemAction.FAVORITE) {
                    // The only action whose meaning is fixed, so it is the only one shown as a
                    // symbol; the custom slots say whatever Settings has named them.
                    FilterChip(
                        selected = isSet,
                        onClick = { onToggleAction(action, !isSet) },
                        label = {
                            Icon(
                                if (isSet) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favourite",
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                } else {
                    FilterChip(
                        selected = isSet,
                        onClick = { onToggleAction(action, !isSet) },
                        label = {
                            Text(
                                settings.labelOf(action),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // A custom name can be anything; past this it stops being a chip
                                // and starts being a paragraph that pushes the group off centre.
                                modifier = Modifier.widthIn(max = CustomActionLabelMaxWidth)
                            )
                        }
                    )
                }
            }
        }

        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "Item info")
        }
    }
}

private val CustomActionLabelMaxWidth = 88.dp

/**
 * The bottom bar of a viewer that has no actions: one control, and all it does is open a panel.
 *
 * Shared by the folder File Viewer and the list item viewer, which are both deliberately unable
 * to change anything about what they are showing. That they look identical is the point - one is
 * reached from a folder and the other from a list, and neither is a place where decisions get
 * made, so neither should grow a control that suggests otherwise.
 */
@Composable
fun MediaInfoBar(contentDescription: String, onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ListeaDimens.RowGap),
        horizontalArrangement = Arrangement.End
    ) {
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = contentDescription)
        }
    }
}

/** One labelled fact about whatever the media screen is showing. */
data class InfoField(val label: String, val value: String)

/**
 * Everything the media screen deliberately stopped saying, on request.
 *
 * A sheet rather than a panel: it is read occasionally and the screen is for the media, so it
 * costs nothing until it is asked for and gives the space straight back. Its content scrolls,
 * which is what keeps it usable in landscape where the sheet itself is short.
 *
 * [rows] are built by the caller. The File Viewer has no list, no completion and no actions, and
 * builds four rows about a file; Review builds rather more. Neither is handed the other's fields
 * to leave blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemInfoSheet(title: String, rows: List<InfoField>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ListeaDimens.PagePadding,
                    end = ListeaDimens.PagePadding,
                    bottom = ListeaDimens.SectionGap
                )
        ) {
            // The full value of what the bar had to ellipsize, wrapped rather than cut.
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(ListeaDimens.SectionGap))
            rows.forEach { row ->
                Text(
                    row.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(ListeaDimens.RowGap))
            }
        }
    }
}

/**
 * What the provider knows about a file that Listea does not store: its type, its size and when it
 * was last written.
 *
 * Nothing is kept for these on a list item, and asking per frame while the user swipes would be
 * one SAF round trip per card. So they are read once, when the Info sheet opens, and the sheet
 * renders without them until the answer arrives.
 */
data class MediaFacts(
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** Blocking I/O, moved off the main thread here rather than at every call site. */
suspend fun readMediaFacts(context: Context, uri: String): MediaFacts = withContext(Dispatchers.IO) {
    val parsed = runCatching { uri.toUri() }.getOrNull() ?: return@withContext MediaFacts()
    val document = runCatching { DocumentFile.fromSingleUri(context, parsed) }.getOrNull()
    MediaFacts(
        mimeType = runCatching { context.contentResolver.getType(parsed) }.getOrNull(),
        sizeBytes = runCatching { document?.length() }.getOrNull()?.takeIf { it > 0 },
        lastModified = runCatching { document?.lastModified() }.getOrNull()?.takeIf { it > 0 }
    )
}

/**
 * The type, size and modified rows every Info sheet ends with, from whatever is known.
 *
 * [fallbackSize] and [fallbackModified] are for the File Viewer, whose folder listing already
 * carries both — it has no reason to wait for a lookup to say what it already knows.
 */
fun mediaFactRows(
    facts: MediaFacts,
    name: String,
    fallbackSize: Long? = null,
    fallbackModified: Long? = null
): List<InfoField> = buildList {
    val type = facts.mimeType ?: name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
    type?.let { add(InfoField("Type", it)) }
    (facts.sizeBytes ?: fallbackSize)?.let { add(InfoField("Size", formatSize(it))) }
    (facts.lastModified ?: fallbackModified)?.let { add(InfoField("Modified", formatTimestamp(it))) }
}
