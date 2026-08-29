package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity

@Composable
fun ListDetailScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    listId: Long,
    onBack: () -> Unit
) {
    val detailFlow = remember(listId) { viewModel.observeDetail(listId) }
    val detail by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    var newItem by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ListItemEntity?>(null) }

    BackHandler { onBack() }

    val current = detail
    if (current == null) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = onBack) { Text("‹ Lists") }
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        return
    }

    fun submitNewItem() {
        viewModel.addItem(listId, newItem)
        newItem = ""
    }

    Column(modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Lists") }
        Text(current.list.title, style = MaterialTheme.typography.titleLarge)
        Text(
            progressLabel(current.completedCount, current.items.size, current.isComplete),
            style = MaterialTheme.typography.bodyMedium,
            color = if (current.isComplete) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(12.dp))

        WebhookSection(
            list = current.list,
            onEnabledChange = { viewModel.setWebhookEnabled(listId, it) },
            onUrlChange = { viewModel.setWebhookUrl(listId, it) },
            onTest = { viewModel.testWebhook(listId) }
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                label = { Text("New item") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitNewItem() })
            )
            Button(onClick = { submitNewItem() }, enabled = newItem.isNotBlank()) { Text("Add") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        if (current.items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("No items yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(current.items, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        onToggle = { viewModel.setItemCompleted(item, it) },
                        onEdit = { editing = item },
                        onDelete = { viewModel.deleteItem(item) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { target ->
        TextPromptDialog(
            title = "Edit item",
            label = "Item text",
            initialText = target.title,
            confirmLabel = "Save",
            onConfirm = { viewModel.renameItem(target.id, it); editing = null },
            onDismiss = { editing = null }
        )
    }
}

/**
 * The list's optional completion action. Collapsed by default so the items stay the focus.
 * Nothing here sends anything on its own: delivery is triggered by the ViewModel when the
 * database reports a real incomplete -> complete transition, or by the Test button.
 */
@Composable
private fun WebhookSection(
    list: ListEntity,
    onEnabledChange: (Boolean) -> Unit,
    onUrlChange: (String) -> Unit,
    onTest: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Seeded once per list so incoming database updates never fight with typing.
    var urlText by remember(list.id) { mutableStateOf(list.webhookUrl) }
    val urlProblem = webhookUrlError(urlText)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Webhook",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (list.webhookEnabled) "On" else "Off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.bodyMedium)
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Send when this list is completed",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = list.webhookEnabled, onCheckedChange = onEnabledChange)
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it; onUrlChange(it) },
                label = { Text("POST URL") },
                singleLine = true,
                isError = urlText.isNotBlank() && urlProblem != null,
                supportingText = urlProblem?.let { problem -> { Text(problem) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onTest, enabled = urlProblem == null) {
                    Text("Test webhook")
                }
                Text(
                    deliveryLabel(list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: ListItemEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
            color = if (item.isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 12.dp)
        )
        IconButton(onClick = onDelete) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
