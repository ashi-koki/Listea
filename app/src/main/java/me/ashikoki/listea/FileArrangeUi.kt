package me.ashikoki.listea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The controls that decide what a listing shows and in what order, and the panels behind them.
 *
 * Written against [FileArrangement] alone — no folder, no list, no ViewModel — which is why the
 * same header sits above a folder's files and a list's items unchanged. All it needs is the
 * arrangement in force, somewhere to send the next one, and the word for what it is arranging.
 */

/**
 * A section heading with a sort control and a filter control on its trailing edge.
 *
 * The two icons say whether they are doing anything, because a filtered listing and a short
 * folder look identical otherwise and a user who has forgotten a filter is on will conclude their
 * files are gone. An active control is drawn in the primary colour inside a filled circle; an
 * inactive one is a plain muted glyph. That is a difference in weight, colour and shape at once,
 * which is what makes it readable at a glance rather than a subtlety to be hunted for.
 */
@Composable
fun ArrangeableSectionHeader(
    title: String,
    arrangement: FileArrangement,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
    noun: String = "files"
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionHeader(title, Modifier.weight(1f))
        ArrangeIconButton(
            icon = Icons.Filled.SwapVert,
            active = !arrangement.isSortDefault,
            contentDescription = if (arrangement.isSortDefault) {
                "Sort $noun"
            } else {
                "Sort $noun: " + arrangement.sort.label
            },
            onClick = onSort
        )
        ArrangeIconButton(
            icon = Icons.Filled.FilterAlt,
            active = !arrangement.isFilterDefault,
            contentDescription = if (arrangement.isFilterDefault) {
                "Filter $noun"
            } else {
                "Filter $noun: on"
            },
            onClick = onFilter
        )
    }
}

/** One header control, filled and tinted while it is doing something. */
@Composable
private fun ArrangeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(ActiveIndicatorSize)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
    }
}

private val ActiveIndicatorSize = 32.dp

/**
 * What the filter is currently keeping out, said once above the listing.
 *
 * Only shown while a filter is on. "4 of 27 files" is the difference between a folder that has
 * gone quiet and a folder being looked at through something, and the empty case says so outright
 * rather than leaving a blank section under a heading.
 *
 * [noun] is what the listing holds — files in a folder, items in a list — because this is also
 * the sentence that tells a user nothing is missing, and it has to name the thing they are
 * looking for.
 */
@Composable
fun FilterSummary(
    shown: Int,
    total: Int,
    noun: String = "file",
    modifier: Modifier = Modifier
) {
    Text(
        text = if (shown == 0) {
            "No ${noun}s match the filter (" + countLabel(total, noun) + " hidden)"
        } else {
            "$shown of " + countLabel(total, noun)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (shown == 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
    )
}

/**
 * The filter panel: three groups of chips, cancelled or applied as one.
 *
 * Edits a draft rather than the live arrangement, which is what makes Cancel mean anything. The
 * listing underneath does not move until Apply, so a filter can be assembled a chip at a time
 * without the folder rearranging itself between taps.
 *
 * Each group leads with All, and All is simply the empty selection — picking it clears the group
 * and picking any member clears All. There is no state in which both are on, because there is no
 * separate All to get out of step.
 */
@Composable
fun FileFilterDialog(
    arrangement: FileArrangement,
    showCheckedGroup: Boolean,
    onDismiss: () -> Unit,
    onApply: (FileArrangement) -> Unit,
    noun: String = "files"
) {
    var draft by remember { mutableStateOf(arrangement) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter $noun") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = PanelMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                FilterGroup(
                    title = "Media type",
                    options = MediaKind.entries,
                    selected = draft.mediaKinds,
                    label = { it.label },
                    onChange = { draft = draft.copy(mediaKinds = it) }
                )
                // Hidden entirely when Folder review integration is off, along with every other
                // trace of checking on this page. A group that filtered on a state the page is
                // deliberately not showing would be a control with invisible consequences.
                if (showCheckedGroup) {
                    Spacer(Modifier.height(ListeaDimens.SectionGap))
                    FilterGroup(
                        title = "Checked status",
                        options = CheckedState.entries,
                        selected = draft.checked,
                        label = { it.label },
                        onChange = { draft = draft.copy(checked = it) }
                    )
                }
                Spacer(Modifier.height(ListeaDimens.SectionGap))
                FilterGroup(
                    title = "Freshness",
                    options = Freshness.entries,
                    selected = draft.freshness,
                    label = { it.label },
                    onChange = { draft = draft.copy(freshness = it) }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One filter group: All, then its members, multi-selectable.
 *
 * Generic over the enum rather than written three times, because the three groups differ only in
 * what they are called and what they hold — and a fourth group later should cost a call, not a
 * copy.
 */
@Composable
private fun <T> FilterGroup(
    title: String,
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onChange: (Set<T>) -> Unit
) {
    SectionHeader(title)
    Spacer(Modifier.height(ListeaDimens.CompactGap))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            // Already showing everything: tapping All again is a no-op rather than a way to
            // select nothing, which would be a filter that hides the whole folder.
            onClick = { onChange(emptySet()) },
            label = { Text("All") }
        )
        options.forEach { option ->
            val isOn = option in selected
            FilterChip(
                selected = isOn,
                onClick = {
                    onChange(if (isOn) selected - option else selected + option)
                },
                label = { Text(label(option)) }
            )
        }
    }
}

/**
 * The sort panel: one order at a time, cancelled or applied like the filter.
 *
 * A radio list rather than a menu that acts on the tap, so the two header controls behave the
 * same way — both open something with a Cancel in it, and neither changes the listing until it is
 * asked to.
 */
@Composable
fun FileSortDialog(
    arrangement: FileArrangement,
    showCheckedSort: Boolean,
    onDismiss: () -> Unit,
    onApply: (FileArrangement) -> Unit,
    noun: String = "files"
) {
    var draft by remember { mutableStateOf(arrangement.sort) }
    val options = remember(showCheckedSort) {
        FileSort.entries.filter { showCheckedSort || it != FileSort.CHECKED }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort $noun") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = PanelMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = ListeaDimens.CompactGap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = draft == option, onClick = { draft = option })
                        Spacer(Modifier.size(ListeaDimens.RowGap))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(arrangement.copy(sort = draft)) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Tall enough for every group, short enough to leave the buttons on screen in landscape. */
private val PanelMaxHeight = 360.dp
