package me.ashikoki.listea

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stable, long-lived configuration, gathered out of the business screens.
 *
 * Composed from two stores on purpose: the browsing root stays in [FolderStore] because it is a
 * SAF grant that already persists correctly, and everything else lives in [SettingsStore]. There
 * is no benefit to moving the root just for architectural tidiness.
 *
 * Switches persist on the spot. Text fields persist when they lose focus or the user presses Done,
 * so a half-typed URL is never written and DataStore is not hit on every keystroke.
 *
 * V4.3 puts each group in the same [SectionCard] the management screens use, so this reads as a
 * page of the app rather than the settings form it used to be. One scroll surface, as everywhere.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: ListsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rootChangePreview by viewModel.rootChangePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember { FolderStore(context) }

    // Re-read after a change so the label follows the newly granted root.
    var rootGeneration by remember { mutableStateOf(0) }
    val rootUri = remember(rootGeneration) { store.read() }
    val rootLabel by produceState("No folder selected", rootUri) {
        value = rootUri?.let {
            withContext(Dispatchers.IO) { sourceFolderLabel(context, it, "") }
        } ?: "No folder selected"
    }

    // Survives the picker's trip through another activity, so a confirmed change is still
    // remembered if this screen is recreated while the user is choosing.
    var discardRootOnPick by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val discard = discardRootOnPick
        discardRootOnPick = null
        // Backing out of the picker changes nothing at all: no new root, no deletions.
        if (uri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (granted) {
            // The confirmed deletion happens here and only here — once a genuinely different
            // root has actually been granted. Re-picking the same folder deletes nothing.
            if (discard != null && discard != uri.toString()) {
                viewModel.discardListsForRoot(discard)
            }
            store.save(uri)
            rootGeneration++
        }
    }

    /** Choosing a first folder costs nothing; replacing one has to be confirmed. */
    fun startFolderChange() {
        val current = rootUri
        if (current == null) pickFolder.launch(null) else viewModel.previewRootChange(current.toString())
    }

    // One scroll surface for the whole page; no subsection scrolls on its own.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListeaDimens.PagePadding),
        verticalArrangement = Arrangement.spacedBy(ListeaDimens.SectionGap)
    ) {
        Spacer(Modifier.height(ListeaDimens.CompactGap))

        SectionCard(title = "Storage") {
            InfoActionsRow(
                info = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ListeaDimens.IconSize)
                        )
                        Text("Root folder", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        rootLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    Button(onClick = { startFolderChange() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (rootUri == null) "Select folder" else "Change folder", maxLines = 1)
                    }
                }
            )
            // The lists that go are the ones linked to the folder being replaced. Nothing is
            // rebound to the new root, and manual lists are never touched.
            HelperText("Changing folder deletes the Lists linked to the current one. Manual Lists are kept.")
        }

        SectionCard(title = "Review") {
            SwitchRow(
                label = "Enable Quick Review in Folder",
                icon = Icons.Filled.Visibility,
                checked = settings.folderQuickReviewEnabled,
                onCheckedChange = { viewModel.setFolderQuickReviewEnabled(it) }
            )
            HelperText(
                "Off turns the Folder tab into a plain browser and gallery. Lists keep their " +
                    "items, completion and actions."
            )

            CustomActionFields(
                title = "Custom action 1",
                displayName = settings.custom1DisplayName,
                webhookValue = settings.custom1WebhookValue,
                onDisplayNameCommit = { viewModel.setCustom1DisplayName(it) },
                onWebhookValueCommit = { viewModel.setCustom1WebhookValue(it) }
            )
            CustomActionFields(
                title = "Custom action 2",
                displayName = settings.custom2DisplayName,
                webhookValue = settings.custom2WebhookValue,
                onDisplayNameCommit = { viewModel.setCustom2DisplayName(it) },
                onWebhookValueCommit = { viewModel.setCustom2WebhookValue(it) }
            )
            HelperText(
                "The display name is what you see on the chips. The webhook value is what future " +
                    "payloads send. Items already marked keep their selection."
            )
        }

        SectionCard(title = "Media and review behaviour") {
            SwitchRow(
                label = "Play videos automatically",
                icon = Icons.Filled.PlayArrow,
                checked = settings.videoAutoplay,
                onCheckedChange = { viewModel.setVideoAutoplay(it) }
            )
            SwitchRow(
                label = "Start videos muted",
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                checked = settings.videoStartMuted,
                onCheckedChange = { viewModel.setVideoStartMuted(it) }
            )
            SwitchRow(
                label = "Remember review position",
                icon = Icons.Outlined.Bookmark,
                checked = settings.rememberReviewPosition,
                onCheckedChange = { viewModel.setRememberReviewPosition(it) }
            )
            SwitchRow(
                label = "Auto-check list freshness on entry",
                icon = Icons.Filled.Refresh,
                checked = settings.autoCheckSourceFreshness,
                onCheckedChange = { viewModel.setAutoCheckSourceFreshness(it) }
            )
            HelperText(
                "Resuming applies to full Review only — Quick Review always starts at the first " +
                    "unchecked item. With auto-checking off, a List says \"Not checked\" until " +
                    "you use Update from folder."
            )
        }

        SectionCard(title = "Webhook") {
            SwitchRow(
                label = "Default enabled",
                checked = settings.defaultWebhookEnabled,
                onCheckedChange = { viewModel.setDefaultWebhookEnabled(it) }
            )
            SettingsTextField(
                label = "Default URL",
                value = settings.defaultWebhookUrl,
                onCommit = { viewModel.setDefaultWebhookUrl(it) },
                allowEmpty = true
            )
            HelperText("Copied into new Lists when they are created. Existing Lists are never changed.")
        }

        Spacer(Modifier.height(ListeaDimens.SectionGap))
    }

    RootChangeDialog(
        preview = rootChangePreview,
        currentRootLabel = rootLabel,
        onCancel = { viewModel.dismissRootChangePreview() },
        onConfirm = { pending ->
            viewModel.dismissRootChangePreview()
            discardRootOnPick = pending.rootUri
            pickFolder.launch(null)
        }
    )
}

/**
 * Spells out what changing the root costs before the picker ever opens: which lists go, what
 * survives, and that nothing happens until a new folder is actually chosen.
 */
@Composable
private fun RootChangeDialog(
    preview: RootChangePreview?,
    currentRootLabel: String,
    onCancel: () -> Unit,
    onConfirm: (RootChangePreview) -> Unit
) {
    val pending = preview ?: return
    val count = pending.titles.size

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Change root folder?") },
        text = {
            Column {
                if (count == 0) {
                    Text("No lists are linked to $currentRootLabel, so nothing will be deleted.")
                } else {
                    Text(
                        "${countOf(count, "list")} linked to $currentRootLabel will be deleted, " +
                            "including any created in its subfolders. Their items, checked " +
                            "state and actions go with them. This cannot be undone."
                    )
                    Spacer(Modifier.height(ListeaDimens.RowGap))
                    pending.titles.take(PREVIEW_TITLES).forEach { title ->
                        Text(
                            "• $title",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (count > PREVIEW_TITLES) {
                        Text(
                            "• and ${count - PREVIEW_TITLES} more",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(ListeaDimens.RowGap))
                Text(
                    "Manual lists, and lists linked to any other folder, are kept. Nothing is " +
                        "deleted unless you go on to choose a new folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pending) }) { Text("Choose new folder") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private const val PREVIEW_TITLES = 5

private fun countOf(count: Int, noun: String): String =
    "$count $noun" + if (count == 1) "" else "s"

/**
 * One custom action's two fields: what it is called, and what it sends.
 *
 * Side by side where both stay readable, stacked where they would not. The threshold is about the
 * fields rather than the device: a text field narrower than this stops showing enough of its own
 * value to edit it, and two cramped fields are worse than two full-width ones.
 */
@Composable
private fun CustomActionFields(
    title: String,
    displayName: String,
    webhookValue: String,
    onDisplayNameCommit: (String) -> Unit,
    onWebhookValueCommit: (String) -> Unit
) {
    Spacer(Modifier.height(ListeaDimens.RowGap))
    Text(title, style = MaterialTheme.typography.titleSmall)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= CustomActionSideBySideMinWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)) {
                SettingsTextField(
                    label = "Display name",
                    value = displayName,
                    onCommit = onDisplayNameCommit,
                    modifier = Modifier.weight(1f)
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = webhookValue,
                    onCommit = onWebhookValueCommit,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column {
                SettingsTextField(
                    label = "Display name",
                    value = displayName,
                    onCommit = onDisplayNameCommit
                )
                SettingsTextField(
                    label = "Webhook value",
                    value = webhookValue,
                    onCommit = onWebhookValueCommit
                )
            }
        }
    }
}

/** Two 150dp fields and the gap between them: below this a field stops showing enough to edit. */
private val CustomActionSideBySideMinWidth = 308.dp

/** The quiet line under a control that explains it, in one place so they all match. */
@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ListeaDimens.IconSize)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A settings text field that commits on Done or on losing focus, never per keystroke.
 *
 * Blank input is refused rather than stored: an empty action name would leave an unlabelled chip
 * and an empty wire value would put `""` in a webhook array. The field says so and the stored
 * value is left alone. [allowEmpty] is for the default URL, where empty legitimately means "none".
 */
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowEmpty: Boolean = false
) {
    // Seeded per stored value so an external change lands, without fighting the keyboard.
    var text by remember(value) { mutableStateOf(value) }
    val error = if (allowEmpty) null else settingsTextError(text)

    fun commit() {
        if (error == null && text.trim() != value) onCommit(text)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}
