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
import me.ashikoki.listea.data.WebhookRecordSummary
import org.json.JSONObject

/**
 * Every webhook body Listea has built, what became of it, and the two things you can do about one.
 *
 * It began as a place for failures — a failed delivery used to leave nothing behind but a dialog
 * the user had already dismissed, and the round it described was gone. Keeping only failures then
 * turned out to be its own gap: "did that round go out?" is the question people actually have,
 * and a page that answers it only when the answer is no is a page you cannot trust. So a record
 * is written for every delivery that reached a verdict — sent, failed, refused by a switched-off
 * webhook, or declined when the app asked — and this page shows all of them.
 *
 * Records are written by the delivery path, never here; this screen only replays them or throws
 * them away. Resending posts to the default URL in Settings, deliberately without asking whether
 * the default webhook is switched *on* — a record may exist precisely because something was
 * misconfigured, and the switch is one of the things it might have been. Both the progress and
 * the result land in the same top-level dialogs every other delivery uses.
 */
@Composable
fun WebhookHistoryScreen(
    modifier: Modifier,
    viewModel: ListsViewModel,
    onBack: () -> Unit
) {
    val records by viewModel.webhookHistory.collectAsStateWithLifecycle()

    // Survives rotation, and is only an id: the payload behind it is read when the dialog opens.
    var openRecordId by rememberSaveable { mutableStateOf<Long?>(null) }

    BackHandler { onBack() }

    ListeaNestedScaffold(
        modifier = modifier,
        title = "Webhook history",
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
                        "Nothing yet",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Every webhook Listea builds is kept here with its payload — sent or " +
                            "not — until you delete it.",
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
                WebhookRecordCard(
                    record = record,
                    onOpen = { openRecordId = record.id },
                    onDelete = { viewModel.deleteWebhookRecord(record.id) },
                    onResend = { viewModel.resendWebhookRecord(record.id) }
                )
            }
        }
    }

    openRecordId?.let { id ->
        WebhookPayloadDialog(
            viewModel = viewModel,
            recordId = id,
            onDismiss = { openRecordId = null }
        )
    }
}

/**
 * One kept payload: when it last reached a verdict, what it was, what that verdict was, and the
 * two actions.
 *
 * The card itself is the way into the body, the same way a list card is the way into its list, so
 * the row keeps to the time and its two buttons. Delete is immediate: what it removes is a record
 * of something that already happened, not anything the user made, and needing two taps each to
 * clear a run of them would make the page tiring in exactly the situation that produces a run.
 *
 * Resend is offered on a successful record too. Sending the same round twice is a thing people
 * legitimately want — a receiver that lost it, a flow that was rebuilt — and the alternative is a
 * button that disappears exactly when the payload is known-good.
 */
@Composable
private fun WebhookRecordCard(
    record: WebhookRecordSummary,
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
                    webhookTimestampLabel(record.at),
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
                StatusLine(record.detail, tone = recordTone(record.outcome))
            }
            TextButton(onClick = onDelete) { Text("Delete", maxLines = 1) }
            TextButton(onClick = onResend) { Text("Resend", maxLines = 1) }
        }
    }
}

/**
 * How loudly a record's own line reads.
 *
 * A delivery that went out is a success and says so; one that failed is a warning. Everything
 * else — switched off, declined — is neither: nothing went wrong, the user or the configuration
 * simply meant it not to go, and colouring that like a failure would make an ordinary choice look
 * like a problem. An outcome from some future version reads neutrally rather than alarmingly.
 */
private fun recordTone(outcome: String): StatusTone = when (outcome) {
    NoticeOutcome.SENT.name -> StatusTone.Positive
    NoticeOutcome.FAILED.name -> StatusTone.Warning
    else -> StatusTone.Neutral
}

/**
 * The body itself, exactly as it was posted or would have been.
 *
 * Read only now, and only for this one record, because a payload is as big as the round it
 * covers. Nothing in here acts on anything: it scrolls, and it closes. Indented for reading, but
 * the stored text is what gets sent, so a body this cannot parse is shown raw rather than hidden.
 */
@Composable
private fun WebhookPayloadDialog(
    viewModel: ListsViewModel,
    recordId: Long,
    onDismiss: () -> Unit
) {
    val payload by produceState<String?>(null, recordId) {
        val stored = viewModel.webhookRecordPayload(recordId)
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
