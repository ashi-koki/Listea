package me.ashikoki.listea

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity

/**
 * The V4.2 media surface, shared by Full Review, Quick Review and the folder File Viewer.
 *
 * One presentation for all three: a thin bar, the media taking everything else, and whatever
 * controls the screen actually has. There is no subtitle slot and no hint slot — not "unused by
 * default" but absent, because the reason the old media screens lost a third of their height was
 * that those slots existed and every caller filled them. What used to live there is in
 * [ItemInfoSheet] now, opened on request.
 */

/**
 * The frame every media screen shares.
 *
 * The media Box is the only thing with a weight, so it absorbs every pixel the bar and the
 * controls do not take, in either orientation. Its background is black rather than a theme
 * surface: [androidx.compose.ui.layout.ContentScale.Fit] letterboxes almost everything, and a
 * photograph reads better against black in a light theme than a light theme reads against a
 * photograph.
 *
 * [chromeVisible] is how a video goes fullscreen: the bar and the controls are not composed at
 * all, so the media Box's weight is the whole screen. The media itself is untouched by the
 * switch — nothing is rebuilt, nothing is remeasured into a different tree, and playback carries
 * straight on across the toggle.
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
    Column(modifier.fillMaxSize()) {
        if (chromeVisible) {
            MediaTopBar(title = title, position = position, onBack = onBack)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            media()
        }
        if (chromeVisible) controls?.invoke()
    }
}

/**
 * Takes the status and navigation bars away while [active], and gives them back on the way out.
 *
 * Only ever driven by the player's own fullscreen button. The transient-swipe behaviour means the
 * bars are always one gesture away, so nothing here can strand the user, and [DisposableEffect]
 * restores them however the screen is left — back, a swipe, or the activity going away.
 */
@Composable
fun FullscreenSystemBars(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        val window = (view.context.findActivity())?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (active && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (active) controller?.show(WindowInsetsCompat.Type.systemBars())
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
 * Every control edits the real [ListItemEntity] through the caller's ViewModel calls, which is
 * what keeps a completion reached in Quick Review indistinguishable from one reached in Review —
 * including the webhook it may fire. Nothing here navigates.
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
    item: ListItemEntity,
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
            checked = item.isCompleted,
            onCheckedChange = onToggleCompleted,
            modifier = Modifier.semantics {
                contentDescription = if (item.isCompleted) "Checked" else "Not checked"
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
                val isSet = action.isSetOn(item)
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
