package me.ashikoki.listea

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ashikoki.listea.data.UnsentWebhookSummary
import org.json.JSONObject

/**
 * Every webhook body that never reached a receiver, and the two things you can do about one.
 *
 * A failed delivery used to leave nothing behind but a dialog the user had already dismissed: the
 * round of review it described was gone, and the only way to send it was to do the round again.
 * This page is that dialog made durable. Records are written by the delivery path, never here,
 * and this screen only replays them or throws them away.
 *
 * Resending posts to the default URL in Settings, deliberately without asking whether the default
 * webhook is switched *on* — a record exists because something was misconfigured, and the switch
 * is one of the things it might have been. Both the result and any failure land in the same
 * top-level dialog every other delivery uses.
 */
@Composable
fun UnsentHistoryScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val records by viewModel.unsentWebhooks.collectAsStateWithLifecycle()

    // Survives rotation, and is only an id: the payload behind it is read when the dialog opens.
    var openRecordId by rememberSaveable { mutableStateOf<Long?>(null) }

    BackHandler { onBack() }

    ListeaNestedScaffold(
        modifier = modifier,
        title = "Unsent history",
        backLabel = "Settings",
        onBack = onBack,
        trailing = {
            Text(
                countOfRecords(records.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) { bodyModifier ->
        if (records.isEmpty()) {
            Box(bodyModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(ListeaDimens.PagePadding)
                ) {
                    Text(
                        "Nothing waiting",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "A webhook that does not come back successful is kept here, with its " +
                            "payload, until you resend or delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@ListeaNestedScaffold
        }

        LazyColumn(
            modifier = bodyModifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ListeaDimens.PagePadding,
                vertical = ListeaDimens.RowGap
            ),
            verticalArrangement = Arrangement.spacedBy(ListeaDimens.RowGap)
        ) {
            items(records, key = { it.id }) { record ->
                UnsentRecordCard(
                    record = record,
                    onOpen = { openRecordId = record.id },
                    onDelete = { viewModel.deleteUnsentWebhook(record.id) },
                    onResend = { viewModel.resendUnsentWebhook(record.id) }
                )
            }
        }
    }

    openRecordId?.let { id ->
        UnsentPayloadDialog(
            viewModel = viewModel,
            recordId = id,
            onDismiss = { openRecordId = null }
        )
    }
}

/**
 * One kept payload: when it failed, what it was, why it did not go, and the two actions.
 *
 * The card itself is the way into the body, the same way a list card is the way into its list, so
 * the row keeps to the failure time and its two buttons. Delete is immediate: what it removes is
 * a failed delivery, not anything the user made, and needing two taps each to clear a run of them
 * would make the page tiring in exactly the situation that produces a run of them.
 */
@Composable
private fun UnsentRecordCard(
    record: UnsentWebhookSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onResend: () -> Unit
) {
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ListeaDimens.CardCorner)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ListeaDimens.CardPadding,
                    top = ListeaDimens.RowGap,
                    bottom = ListeaDimens.RowGap
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListeaDimens.CompactGap)
            ) {
                Text(
                    webhookTimestampLabel(record.failedAt),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusLine(
                    listOf(
                        webhookEventLabel(record.event),
                        record.listTitle,
                        countOfItems(record.itemCount)
                    ).joinToString(" · ")
                )
                StatusLine(record.reason, tone = StatusTone.Warning)
            }
            TextButton(onClick = onDelete) { Text("Delete", maxLines = 1) }
            TextButton(onClick = onResend) { Text("Resend", maxLines = 1) }
        }
    }
}

/**
 * The body itself, exactly as it would have been posted.
 *
 * Read only now, and only for this one record, because a payload is as big as the round it
 * covers. Nothing in here acts on anything: it scrolls, and it closes. Indented for reading, but
 * the stored text is what gets sent, so a body this cannot parse is shown raw rather than hidden.
 */
@Composable
private fun UnsentPayloadDialog(
    viewModel: ListsViewModel,
    recordId: Long,
    onDismiss: () -> Unit
) {
    val payload by produceState<String?>(null, recordId) {
        val stored = viewModel.unsentWebhookPayload(recordId)
        value = stored?.let { withContext(Dispatchers.Default) { prettyJson(it) } } ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payload") },
        text = {
            Box(Modifier.heightIn(max = PayloadMaxHeight)) {
                Text(
                    payload ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Tall enough to be worth scrolling, short enough that the dialog stays a dialog. */
private val PayloadMaxHeight = 420.dp

/** Indented JSON, or the original text when it is not JSON this can re-read. */
private fun prettyJson(payload: String): String =
    runCatching { JSONObject(payload).toString(2) }.getOrDefault(payload)

private fun countOfRecords(count: Int): String =
    "$count record" + if (count == 1) "" else "s"

private fun countOfItems(count: Int): String =
    "$count item" + if (count == 1) "" else "s"
