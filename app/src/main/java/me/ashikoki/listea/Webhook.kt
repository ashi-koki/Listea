package me.ashikoki.listea

import android.os.SystemClock
import android.util.Log
import me.ashikoki.listea.data.ListEntity
import me.ashikoki.listea.data.ListItemEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fired when a list transitions from incomplete to complete. */
const val EVENT_LIST_COMPLETED = "list.completed"

/** Fired by the "Test webhook" button. Never changes completion state. */
const val EVENT_WEBHOOK_TEST = "list.webhook.test"

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
 * The webhook body. Timestamps are epoch milliseconds. This is a deliberately fixed shape:
 * it exposes the list and its items, and nothing about how they are stored.
 */
fun buildWebhookPayload(event: String, list: ListEntity, items: List<ListItemEntity>): String {
    val itemsJson = JSONArray()
    items.forEach { item ->
        itemsJson.put(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("isCompleted", item.isCompleted)
        )
    }
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
 * POSTs [jsonBody] to [url]. Blocking: call on an IO dispatcher.
 * Any 2xx counts as success; every other status code and every network failure is a failure.
 * Never throws.
 */
fun postWebhook(url: String, jsonBody: String): DeliveryResult {
    var connection: HttpURLConnection? = null
    val startedAt = SystemClock.elapsedRealtime()
    fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    var connectedMs = -1L
    var sentMs = -1L

    return try {
        val target = URL(url)
        connection = (target.openConnection() as HttpURLConnection).apply {
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
                "-> POST $target host=${target.host} port=${target.port} " +
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
                "redirected=${connection.url.toString() != target.toString()} " +
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
        DeliveryResult(DeliveryStatus.FAILED, null, shortError(e))
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

private fun shortError(e: Throwable): String = when (e) {
    is SocketTimeoutException -> "Timeout"
    is UnknownHostException -> "Host not found"
    else -> (e.message ?: e::class.java.simpleName).take(120)
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
