package me.ashikoki.listea

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
 */
@Composable
fun SettingsScreen(
    modifier: Modifier,
    viewModel: ListsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rootChangePreview by viewModel.rootChangePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember { FolderStore(context) }

    BackHandler { onBack() }

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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SettingsGroup("Storage") {
            Text("Root folder", style = MaterialTheme.typography.bodyMedium)
            Text(
                rootLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { startFolderChange() }) {
                Text(if (rootUri == null) "Select folder" else "Change folder")
            }
            Text(
                "Changing folder deletes the lists linked to the current one. Manual lists are " +
                    "kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsGroup("Review") {
            SwitchRow(
                label = "Enable Quick Review in Folder",
                checked = settings.folderQuickReviewEnabled,
                onCheckedChange = { viewModel.setFolderQuickReviewEnabled(it) }
            )
            Text(
                "Off turns the Folder tab into a plain browser and gallery. Lists keep their " +
                    "items, completion and actions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Text("Custom action 1", style = MaterialTheme.typography.bodyMedium)
            SettingsTextField(
                label = "Display name",
                value = settings.custom1DisplayName,
                onCommit = { viewModel.setCustom1DisplayName(it) }
            )
            SettingsTextField(
                label = "Webhook value",
                value = settings.custom1WebhookValue,
                onCommit = { viewModel.setCustom1WebhookValue(it) }
            )

            Spacer(Modifier.height(12.dp))
            Text("Custom action 2", style = MaterialTheme.typography.bodyMedium)
            SettingsTextField(
                label = "Display name",
                value = settings.custom2DisplayName,
                onCommit = { viewModel.setCustom2DisplayName(it) }
            )
            SettingsTextField(
                label = "Webhook value",
                value = settings.custom2WebhookValue,
                onCommit = { viewModel.setCustom2WebhookValue(it) }
            )
            Text(
                "Renaming changes what you see and what future webhooks send. Items already " +
                    "marked keep their selection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsGroup("Media") {
            SwitchRow(
                label = "Play videos automatically",
                checked = settings.videoAutoplay,
                onCheckedChange = { viewModel.setVideoAutoplay(it) }
            )
            SwitchRow(
                label = "Start videos muted",
                checked = settings.videoStartMuted,
                onCheckedChange = { viewModel.setVideoStartMuted(it) }
            )
            Text(
                "Applies to Review, Quick Review and the folder viewer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsGroup("Webhook") {
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
            Text(
                "Copied into new lists when they are created. Existing lists are never changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsGroup("Source updates") {
            InfoRow("Auto-check source changes", "On")
            InfoRow("Auto-update list", "Coming later")
            Text(
                "Listea checks a list's source folder when you open it, and never changes the " +
                    "list without you asking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
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
                    Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
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

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Column { content() }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Static state that V3.8 reports but does not offer to change. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) commit() }
    )
}
