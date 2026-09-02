package me.ashikoki.listea

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The handful of measurements the V4 screens share.
 *
 * Small on purpose: enough that page padding and section spacing agree everywhere, not a design
 * system. Colour and type come from [MaterialTheme].
 */
object ListeaDimens {
    /** Horizontal breathing room for page content. */
    val PagePadding = 16.dp

    /** Between one block of a page and the next. */
    val SectionGap = 16.dp

    /** Between rows, chips and buttons sitting together. */
    val RowGap = 8.dp

    /** Between a label and the thing it labels. */
    val CompactGap = 4.dp

    /** Inside a card, between its border and its content. */
    val CardPadding = 12.dp

    val CardCorner = 12.dp
    val IconSize = 20.dp
}

/**
 * The three places the app is *about*. Everything else is reached from one of them and is nested
 * beneath it.
 */
enum class TopLevelDestination(val label: String, val title: String, val icon: ImageVector) {
    Folder("Folder", "Listea", Icons.Filled.Folder),
    Lists("Lists", "Lists", Icons.AutoMirrored.Filled.List),
    Settings("Settings", "Settings", Icons.Filled.Settings)
}

/**
 * The one shell every screen is rendered inside.
 *
 * There is a single [Scaffold] for the whole app rather than one per screen, and [showChrome]
 * hides the bar and the navigation instead of swapping shells. That matters: the content slot
 * keeps its position in the composition when a nested screen opens, so opening Quick Review or a
 * file viewer cannot remount the Folder screen underneath and throw away where the user was.
 *
 * Nested screens supply their own back affordance through [ListeaNestedScaffold] or [MediaShell],
 * and their own system-bar insets. The shell stops insetting its content the moment it stops
 * drawing chrome, because the one thing a nested media screen must be able to do is fill the
 * display - status bar and navigation bar included - and it cannot do that through a padding the
 * shell has already applied on its behalf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeaTopLevelScaffold(
    destination: TopLevelDestination,
    onSelectDestination: (TopLevelDestination) -> Unit,
    showChrome: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = if (showChrome) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            if (showChrome) {
                TopAppBar(title = { Text(destination.title) })
            }
        },
        bottomBar = {
            if (showChrome) {
                ListeaBottomNavigation(destination, onSelectDestination)
            }
        }
    ) { insets ->
        content(Modifier.padding(insets))
    }
}

@Composable
fun ListeaBottomNavigation(
    destination: TopLevelDestination,
    onSelectDestination: (TopLevelDestination) -> Unit
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { entry ->
            NavigationBarItem(
                selected = destination == entry,
                onClick = { onSelectDestination(entry) },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(entry.label) }
            )
        }
    }
}

/**
 * A nested page: back, a title, optional trailing content, and a body.
 *
 * No bottom navigation — a nested page is somewhere you came *from* somewhere, and the way out is
 * back, not sideways. The bar is compact so the body gets the screen, which is the point of
 * taking the old app-wide header and tab row off these pages.
*
 * It insets itself against the system bars, because the shell no longer does that for a nested
 * screen - see [ListeaTopLevelScaffold]. Only the media screens want the space under those bars.
 */
@Composable
fun ListeaNestedScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit
) {
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = ListeaDimens.RowGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            backLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = ListeaDimens.RowGap)
            )
            trailing?.invoke()
        }
        HorizontalDivider()
        content(Modifier.weight(1f))
    }
}

/**
 * "1 file", "4 files": the one place the app pluralises a count of something.
 *
 * There were two identical private copies of this, in the Folder screen and in Settings, and a
 * third caller was about to make it three.
 */
fun countLabel(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/** A quiet heading above a group of rows. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}
