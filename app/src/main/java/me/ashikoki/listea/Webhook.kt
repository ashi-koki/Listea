package me.ashikoki.listea

import android.os.SystemClock
import android.util.Log
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import me.ashikoki.listea.data.decisions
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

/** Fired when a list transitions from incomplete to complete. */
const val EVENT_LIST_COMPLETED = "list.completed"

/** Fired by the "Test webhook" button. Never changes completion state. */
const val EVENT_WEBHOOK_TEST = "list.webhook.test"

/**
 * Fired when a Quick Review reaches the end of its queue, if the user has switched Quick Review
 * webhooks on. Its own event rather than [EVENT_LIST_COMPLETED] because the owning list is
 * usually *not* complete: one folder of it has been reviewed. The `items` are that folder's
 * queue, and `list` is the list they belong to.
 */
const val EVENT_QUICK_REVIEW_COMPLETED = "quickreview.completed"

/**
 * Fired when the user leaves a review, if the user has switched the on-exit webhook on. Carries
 * the queue that was being reviewed — one folder for Quick Review, the whole list for Review —
 * which is what makes "send the decisions of this round" possible when the queue was itself
 * narrowed to unchecked items.
 */
const val EVENT_REVIEW_EXITED = "review.exited"

private const val TIMEOUT_MS = 10_000

// --- TEMPORARY V3 DELIVERY DIAGNOSTICS -------------------------------------------------------
// Logcat only: nothing here changes what is sent, what is stored, or what the UI shows.
// Filter with:  adb logcat -s ListeaWebhook
// Remove this block (and the Log calls in postWebhook) once the n8n endpoint is understood.
private const val TAG = "ListeaWebhook"
private const val DIAGNOSTICS = true
private const val SNIPPET_BYTES = 512
// ---------------------------------------------------------------------------------------------

enum class DeliveryStatus { SUCCESS, FAILED }

data class DeliveryResult(
    val status: DeliveryStatus,
    val httpCode: Int?,
    val error: String?
)

/**
 * How a completion ended up, from the user's point of view. Distinct from [DeliveryStatus], which
 * is only ever about a delivery that was actually attempted and is what gets persisted; [DISABLED]
 * and [EMPTY] never reach the database, because in neither case was anything sent.
 */
enum class NoticeOutcome {
    SENT,
    FAILED,
    DISABLED,

    /**
     * The webhook was usable and there was simply nothing to put in it: no items at all, or none
     * left once "send checked items only" had its say. Posting `"items": []` would tell a
     * receiver a round happened when none did, so the user is told instead.
     */
    EMPTY
}

/**
 * What one completion should tell the user, so a result never has to be hunted for on a folder
 * card or a list screen.
 *
 * Raised for every attempt that reached a verdict, including one abandoned over an unusable URL
 * (which still records a failed delivery), and for a list that completed with its webhook switched
 * off — otherwise that case is the one the user hears nothing about at all.
 * [itemCount] is null when no payload was ever built.
 */
data class WebhookNotice(
    val event: String,
    val listTitle: String,
    val host: String?,
    val itemCount: Int?,
    val outcome: NoticeOutcome,
    val httpCode: Int?,
    val error: String?,
    /** Whether the app's default webhook was used rather than the list's own, so a switched-off
     * webhook can name the switch the user actually has to change. */
    val defaultWebhook: Boolean,
    val at: Long
) {
    val succeeded: Boolean get() = outcome == NoticeOutcome.SENT

    /**
     * Whether anything left the device, or was going to. False means nothing was even tried, and
     * a second notice saying the same is worth collapsing rather than showing twice.
     */
    val attempted: Boolean
        get() = outcome == NoticeOutcome.SENT || outcome == NoticeOutcome.FAILED

    val headline: String
        get() = when (outcome) {
            NoticeOutcome.SENT -> "Webhook sent"
            NoticeOutcome.FAILED -> "Webhook not sent"
            NoticeOutcome.DISABLED -> "Webhook is off"
            NoticeOutcome.EMPTY -> "Nothing to send"
        }

    val eventLabel: String get() = webhookEventLabel(event)

    /** "Success · HTTP 200", "Failed · HTTP 500", "Failed · Timeout". */
    val outcomeLabel: String
        get() = when (outcome) {
            NoticeOutcome.SENT -> "Success" + (httpCode?.let { " · HTTP $it" } ?: "")
            NoticeOutcome.FAILED -> "Failed · " + (httpCode?.let { "HTTP $it" } ?: error ?: "Failed")
            NoticeOutcome.DISABLED -> "Nothing was sent · " + if (defaultWebhook) {
                "the default webhook is switched off"
            } else {
                "this list's webhook is switched off"
            }
            NoticeOutcome.EMPTY -> "Nothing was sent · there were no items to send"
        }

    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}

/**
 * What an event is called in front of the user. One mapping, so a live result and a record of an
 * old one can never name the same event differently. An unrecognised event answers with itself
 * rather than with nothing.
 */
fun webhookEventLabel(event: String): String = when (event) {
    EVENT_LIST_COMPLETED -> "List completed"
    EVENT_WEBHOOK_TEST -> "Test webhook"
    EVENT_QUICK_REVIEW_COMPLETED -> "Quick Review completed"
    EVENT_REVIEW_EXITED -> "Review left"
    else -> event
}

/**
 * A full "2026-09-01 16:40:05" stamp, for a record that has to be told apart from the others
 * rather than placed in the last few minutes. Live notices use the shorter [WebhookNotice.timeLabel].
 */
fun webhookTimestampLabel(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

/**
 * Whether this notice tells the user anything the ones already waiting have not.
 *
 * A delivery that was attempted always does: two attempts are two events, and hiding one would
 * misrepresent what left the device. A result where nothing was attempted does not, if the same
 * list has already produced that same result — one swipe can finish a review and complete its
 * list at the same moment, and being told twice over that a webhook is switched off is noise.
 */
fun WebhookNotice.addsTo(queued: List<WebhookNotice>): Boolean =
    attempted || queued.none { it.outcome == outcome && it.listTitle == listTitle }

/** Host of a webhook URL, for showing where something went without exposing the full URL. */
fun webhookHost(url: String): String? =
    runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

/** Returns a user-readable problem with [url], or null when it is usable. */
fun webhookUrlError(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "Enter a webhook URL"
    val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return "Not a valid URL"
    if (parsed.protocol !in setOf("http", "https")) return "URL must start with http:// or https://"
    if (parsed.host.isNullOrBlank()) return "URL is missing a host"
    return null
}

/**
 * The items a payload should actually carry, out of the ones the event covers.
 *
 * The one place the "only send checked items" setting is applied, so it holds for every event
 * including the test one: what the test button sends stays an honest preview of a real delivery.
 * An event whose items are all unchecked legitimately sends an empty `items` array — a receiver
 * being told "nothing was checked" is not the same as it being told nothing.
 */
fun webhookItems(items: List<ListItemEntity>, settings: AppSettings): List<ListItemEntity> =
    if (settings.webhookCompletedItemsOnly) items.filter { it.isCompleted } else items

/**
 * The webhook body. Timestamps are epoch milliseconds. This is a deliberately fixed shape:
 * it exposes the list and its items, and nothing about how they are stored.
 *
 * Every event uses this one builder, so there is only ever one item payload format on the wire.
 */
fun buildWebhookPayload(
    event: String,
    list: ListEntity,
    items: List<ListItemEntity>,
    settings: AppSettings
): String {
    val itemsJson = JSONArray()
    items.forEach { itemsJson.put(itemJson(list, it, settings)) }
    return JSONObject()
        .put("event", event)
        .put(
            "list",
            JSONObject()
                .put("id", list.id)
                .put("title", list.title)
                .put("completedAt", list.completedAt ?: JSONObject.NULL)
        )
        .put("items", itemsJson)
        .toString()
}

/**
 * One item as the receiver sees it. Completion and actions are separate on purpose: `isCompleted`
 * says the item was processed in Listea, `actions` says what downstream automation should do with
 * it, and neither implies the other. Check/uncheck is never an action.
 *
 * `id` is the item's public id and never its row id — a string, unique across reinstalls and
 * across both review modes, and in an order that sorts by when the item arrived. See
 * [me.ashikoki.listea.data.newItemPublicId] for what that is worth and what it is made of. The
 * row id is not on the wire at all: it was only ever meaningful inside this one database, and
 * sending it invited a receiver to key on it.
 *
 * Action wire values come from [settings] as they stand right now, not from whatever they were
 * when the user tapped the chip.
 */
private fun itemJson(list: ListEntity, item: ListItemEntity, settings: AppSettings): JSONObject {
    val actions = JSONArray()
    itemActionNames(item.decisions, settings).forEach { actions.put(it) }
    return JSONObject()
        .put("id", item.publicId)
        .put("title", item.title)
        .put("isCompleted", item.isCompleted)
        .put("relativePath", webhookRelativePath(list, item) ?: JSONObject.NULL)
        .put("actions", actions)
}

/**
 * The item's *folder*, relative to the selected SAF synchronization root, e.g. `2026-07-11/bilibili`
 * for an item stored at `2026-07-11/bilibili/a.jpg`: the list's root-relative folder path followed
 * by the item's list-relative subfolders, with the file name dropped. The name is already on the
 * wire as `title`, so a receiver that wants the whole path joins the two.
 *
 * Empty for an item sitting directly in a list linked to the root itself, which is exactly that
 * item's folder. Null for manual items, which have no source file at all; a fake path would be
 * worse than none for a downstream tool.
 */
fun webhookRelativePath(list: ListEntity, item: ListItemEntity): String? {
    val itemSegments = pathSegments(item.sourceRelativePath)
    if (itemSegments.isEmpty()) return null
    return (pathSegments(list.sourceRelativePath) + itemSegments.dropLast(1)).joinToString("/")
}

/** Splits on "/" and drops empty segments, so leading, trailing and doubled separators cannot survive. */
private fun pathSegments(path: String?): List<String> =
    path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()

/**
 * POSTs [jsonBody] to [url]. Blocking: call on an IO dispatcher.
 * Any 2xx counts as success; every other status code and every network failure is a failure.
 * Never throws.
 */
fun postWebhook(url: String, jsonBody: String): DeliveryResult {
    var connection: HttpURLConnection? = null
    // Kept outside the try so a failure can still say where it was going.
    var target: URL? = null
    val startedAt = SystemClock.elapsedRealtime()
    fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    var connectedMs = -1L
    var sentMs = -1L

    return try {
        val destination = URL(url)
        target = destination
        connection = (destination.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        if (DIAGNOSTICS) {
            Log.d(
                TAG,
                "-> POST $destination host=${destination.host} port=${destination.port} " +
                    "followRedirects=${connection.instanceFollowRedirects} " +
                    "connectTimeout=${TIMEOUT_MS}ms readTimeout=${TIMEOUT_MS}ms " +
                    "bodyBytes=${bodyBytes.size}"
            )
        }

        // Explicit connect so DNS+TCP+TLS can be timed separately from sending the body.
        // Behaviourally identical: getOutputStream() would trigger the same connect.
        connection.connect()
        connectedMs = elapsed()

        connection.outputStream.use { it.write(bodyBytes) }
        sentMs = elapsed()

        val code = connection.responseCode
        val headersMs = elapsed()
        val success = code in 200..299

        // Drain the body so the connection can be released; for failures keep a short snippet
        // for the log only. The content is still never stored or shown in the UI.
        val snippet = drainBody(
            stream = if (success) connection.inputStream else connection.errorStream,
            keepSnippet = !success
        )

        if (DIAGNOSTICS) {
            val line = "<- status=$code finalUrl=${connection.url} " +
                "redirected=${connection.url.toString() != destination.toString()} " +
                "contentType=${connection.getHeaderField("Content-Type")} " +
                "connect=${connectedMs}ms sent=${sentMs}ms headers=${headersMs}ms total=${elapsed()}ms"
            if (success) Log.d(TAG, line) else Log.w(TAG, "$line body=$snippet")
        }

        if (success) {
            DeliveryResult(DeliveryStatus.SUCCESS, code, null)
        } else {
            DeliveryResult(DeliveryStatus.FAILED, code, "HTTP $code")
        }
    } catch (e: Exception) {
        if (DIAGNOSTICS) {
            Log.w(
                TAG,
                "!! ${e.javaClass.name}: ${e.message} " +
                    "cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message} " +
                    "connect=${connectedMs}ms sent=${sentMs}ms failedAt=${elapsed()}ms"
            )
        }
        DeliveryResult(DeliveryStatus.FAILED, null, webhookErrorLabel(e, target))
    } finally {
        connection?.disconnect()
    }
}

/** Reads and discards the response, optionally keeping a capped, single-line snippet. */
private fun drainBody(stream: InputStream?, keepSnippet: Boolean): String? = runCatching {
    stream?.use { input ->
        val head = ByteArray(SNIPPET_BYTES)
        var filled = 0
        while (filled < head.size) {
            val n = input.read(head, filled, head.size - filled)
            if (n <= 0) break
            filled += n
        }
        val scratch = ByteArray(4096)
        while (input.read(scratch) > 0) { /* discard the rest */ }
        if (keepSnippet && filled > 0) {
            String(head, 0, filled, Charsets.UTF_8).replace(Regex("""\s+"""), " ").trim()
        } else {
            null
        }
    }
}.getOrNull()

/**
 * A network failure in words that describe the delivery, not the JDK.
 *
 * The platform's own wording for these is shaped like `Failed to connect to
 * example.com/93.184.216.34:443` — hostname, slash, *resolved address*, port. That reads exactly
 * like a URL with a path, so a reader compares it against the URL they configured, finds a
 * stretch of it replaced by numbers, and reasonably concludes the app mangled their address.
 * Nothing was rewritten: the numbers are what DNS answered with.
 *
 * So the target is named from the [URL] Listea actually opened, and the resolved address is left
 * to the logcat diagnostics, which is where an address worth arguing with belongs. [target] is
 * null only when the URL itself would not parse, and then the message simply says less.
 */
internal fun webhookErrorLabel(e: Throwable, target: URL?): String {
    val where = target?.let { url ->
        // The effective port, so https says 443 rather than the -1 an unstated port parses to.
        url.host + ":" + (if (url.port != -1) url.port else url.defaultPort)
    }
    fun at(prefix: String) = if (where == null) prefix else "$prefix $where"
    return when (e) {
        is SocketTimeoutException -> at("Timed out reaching")
        is UnknownHostException -> at("Host not found:")
        is ConnectException -> at("Could not connect to")
        is SSLException -> at("TLS failed for")
        else -> (e.message ?: e::class.java.simpleName).take(120)
    }
}

/**
 * Compact webhook state for a browse row, e.g. "Webhook · On · Failed · HTTP 500".
 * Deliberately omits the URL, timestamp and error text so folder cards and list rows stay
 * glanceable; the List detail screen shows the fuller [deliveryLabel] instead.
 *
 * Two independent facts, and both are always reported: whether the webhook will fire from here
 * on, and what happened the last time it did. Switching a webhook off says nothing about what it
 * already sent, so a list that delivered and was then switched off reads "Off · Success" rather
 * than losing its history to a toggle. A delivery always records its timestamp and status
 * together, so a null [lastDeliveryStatus] means nothing has ever been sent.
 */
fun webhookStatusLabel(
    enabled: Boolean,
    lastDeliveryStatus: String?,
    lastDeliveryCode: Int?
): String {
    val delivery = when (lastDeliveryStatus) {
        null -> "Never sent"
        DeliveryStatus.SUCCESS.name -> "Success"
        else -> "Failed" + (lastDeliveryCode?.let { " · HTTP $it" } ?: "")
    }
    return "Webhook · " + (if (enabled) "On" else "Off") + " · " + delivery
}

/** "Last delivery: Success / 18:42", or a short failure reason. */
fun deliveryLabel(list: ListEntity): String {
    val at = list.lastDeliveryAt ?: return "Last delivery: never sent"
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    return if (list.lastDeliveryStatus == DeliveryStatus.SUCCESS.name) {
        "Last delivery: Success · $time"
    } else {
        val reason = list.lastDeliveryCode?.let { "HTTP $it" } ?: list.lastDeliveryError ?: "Failed"
        "Last delivery: Failed · $reason · $time"
    }
}
