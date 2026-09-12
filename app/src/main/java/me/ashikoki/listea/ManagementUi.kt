package me.ashikoki.listea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The visual primitives the three management screens — Folder, Lists and List Detail — genuinely
 * share.
 *
 * Deliberately four small things and no framework. Each one exists because at least two screens
 * were otherwise writing the same Row of a tinted bodySmall, the same bordered block, or the same
 * button row that could not fit. Anything used in one place only stays in the screen that uses it.
 */

/** How a status reads at a glance. Nothing here decides *what* a status is, only its colour. */
enum class StatusTone { Neutral, Positive, Warning }

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.Positive -> MaterialTheme.colorScheme.primary
    StatusTone.Warning -> MaterialTheme.colorScheme.error
}

/**
 * A bordered block of related information, with an optional quiet heading.
 *
 * Used for the current-folder summary and for List Detail's Source and Progress sections. It is
 * an outline rather than a filled surface so that several of them down one page still read as one
 * page, and it sizes to its content — no section reserves height it is not using.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ListeaDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
        ) {
            title?.let { SectionHeader(it) }
            content()
        }
    }
}

/**
 * One line of compact status: an optional icon and a short tinted string.
 *
 * Single-line and ellipsizing by default, because every caller feeds it something whose length it
 * does not control — a webhook result, a folder's progress, a freshness verdict.
 */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null,
    maxLines: Int = 1
) {
    val color = toneColor(tone)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A group of actions that wraps instead of overflowing.
 *
 * This is the fix for the V4.0 folder action row: four buttons in one unbreakable [Row] pushed
 * "Create List" off the right edge of a narrow portrait screen, which made an action that is
 * always valid unreachable. A [FlowRow] moves what does not fit onto the next line, so the layout
 * degrades by getting taller rather than by hiding what the user came for. Wide screens still get
 * a single row for free.
 *
 * Callers pass ordinary buttons and chips; nothing here decides which actions exist.
 */
@Composable
fun ActionGroup(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(ListeaDimens.RowGap),
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * A small tinted chip: a folder's type, an item's tag.
 *
 * Colours are the caller's, because what a chip means is the caller's business. All this owns is
 * the shape, the padding and the type scale, so a "Sublist" chip and a "C1" chip are recognisably
 * the same kind of thing.
 */
@Composable
fun ListeaBadge(
    text: String,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = container,
        contentColor = contentColor
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * A folder's relationship to a List, as a badge: None / List / Sublist.
 *
 * Shown on the current-folder card and on every directory card, which is exactly why it is here:
 * the two must never describe the same folder differently, so they read from one component and
 * one [FolderType].
 */
@Composable
fun FolderTypeLabel(type: FolderType, modifier: Modifier = Modifier) {
    ListeaBadge(
        text = type.label,
        container = when (type) {
            FolderType.LIST -> MaterialTheme.colorScheme.primaryContainer
            FolderType.SUBLIST -> MaterialTheme.colorScheme.secondaryContainer
            FolderType.NONE -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when (type) {
            FolderType.LIST -> MaterialTheme.colorScheme.onPrimaryContainer
            FolderType.SUBLIST -> MaterialTheme.colorScheme.onSecondaryContainer
            FolderType.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
    )
}

/**
 * Information on the left, the actions that apply to it on the right.
 *
 * A card of four short status lines leaves most of a phone's width empty if its actions are
 * stacked underneath, so above [sideBySideMinWidth] the two share the row and the card is roughly
 * half as tall. Below it there is no honest way to fit both, and the actions move underneath
 * rather than being squeezed — the same rule as [ActionGroup], applied to a whole section.
 *
 * Both slots are [ColumnScope], so a caller writes its buttons once and they stack either way;
 * whether they fill the action column or sit at their natural width is the caller's choice.
 *
 * [actionColumnWidth] is fixed rather than measured from the buttons: the column has to be wide
 * enough for its longest label, and a caller that knows its labels can say so more cheaply and
 * more predictably than a nested intrinsic measurement can work it out.
 */
@Composable
fun InfoActionsRow(
    info: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    sideBySideMinWidth: Dp = 320.dp,
    actionColumnWidth: Dp = 170.dp
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= sideBySideMinWidth) {
            Row(
                // Intrinsic height so the divider spans whichever column is taller, rather than
                // the row growing to some fixed guess.
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap),
                    content = info
                )
                VerticalDivider()
                Column(
                    modifier = Modifier.width(actionColumnWidth),
                    verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap),
                    content = actions
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)) {
                info()
                HorizontalDivider(Modifier.padding(vertical = ListeaDimens.CompactGap))
                actions()
            }
        }
    }
}

/**
 * Every file a deletion would take, as folder-then-files, in a box of its own that scrolls.
 *
 * Shared by the two dialogs that can delete files: the one Settings offers over the whole root,
 * and the one a delivered webhook offers over the round it just sent. They ask different
 * questions about different sets and each says so in its own words above this — but what is at
 * stake is a list of filenames either way, and the two must not be able to present that list
 * differently.
 *
 * The listing is the point of both dialogs. "Delete 84 files" is not something anyone can agree
 * to honestly, so every file is named, under the folder that holds it, and the whole thing
 * scrolls — nothing is summarised away with "and 79 more", because the 79 are exactly what is at
 * stake. Grouping by folder keeps that readable at the length it can reach.
 *
 * Lazy because the list is as long as the user's review has been: a folder library can put
 * thousands of names in here, and composing all of them to show the first twenty would stall the
 * dialog exactly when it must not. Monospace so a run of similar filenames stays scannable.
 */
@Composable
fun CheckedFileGroups(
    groups: List<CheckedFileGroup>,
    rootLabel: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = CheckedListingMaxHeight),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
    ) {
        items(groups, key = { it.folderPath }) { group ->
            Column {
                Text(
                    group.folderPath.ifEmpty { rootLabel },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    group.filesLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Tall enough to be worth scrolling, short enough that the dialog stays a dialog. */
private val CheckedListingMaxHeight = 320.dp
