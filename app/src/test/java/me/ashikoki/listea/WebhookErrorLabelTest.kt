package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * How a failed delivery describes itself.
 *
 * These exist because the platform's own wording — `Failed to connect to host/93.184.216.34:443`
 * — is indistinguishable from a URL whose path has been replaced by numbers, and was read as the
 * app having rewritten the address. The rule these pin down is that a failure names the host that
 * was configured, and never puts a resolved address where a path would be.
 */
class WebhookErrorLabelTest {

    private val target = URL("https://n8n.example.me/webhook/60561684-bdbd-4816-80ff-18d4a3e9974a")

    @Test
    fun `a connect failure names the host and port, not a resolved address`() {
        val label = webhookErrorLabel(
            ConnectException("Failed to connect to n8n.example.me/211.162.78.10:443"),
            target
        )

        assertEquals("Could not connect to n8n.example.me:443", label)
        // The address DNS answered with is a logcat matter; putting it here is what read as a
        // mangled URL in the first place.
        assertFalse(label.contains("211.162.78.10"))
    }

    @Test
    fun `an unstated port is reported as the one the scheme actually uses`() {
        assertTrue(webhookErrorLabel(ConnectException(), target).endsWith(":443"))
        assertTrue(
            webhookErrorLabel(ConnectException(), URL("http://example.com/hook")).endsWith(":80")
        )
        assertTrue(
            webhookErrorLabel(ConnectException(), URL("https://example.com:8443/hook"))
                .endsWith(":8443")
        )
    }

    @Test
    fun `the path of the webhook is never part of the failure`() {
        // The whole confusion was a message that looked like a URL. None of these may.
        listOf(
            ConnectException(),
            SocketTimeoutException(),
            UnknownHostException(),
            SSLHandshakeException("handshake failed")
        ).forEach { cause ->
            val label = webhookErrorLabel(cause, target)
            assertFalse(label, label.contains("/webhook/"))
            assertTrue(label, label.contains("n8n.example.me"))
        }
    }

    @Test
    fun `each kind of failure says which kind it is`() {
        assertTrue(webhookErrorLabel(SocketTimeoutException(), target).startsWith("Timed out"))
        assertTrue(webhookErrorLabel(UnknownHostException(), target).startsWith("Host not found"))
        assertTrue(webhookErrorLabel(ConnectException(), target).startsWith("Could not connect"))
        assertTrue(
            webhookErrorLabel(SSLHandshakeException("bad cert"), target).startsWith("TLS failed")
        )
    }

    @Test
    fun `a URL that never parsed still produces something readable`() {
        assertEquals("Could not connect to", webhookErrorLabel(ConnectException(), null))
    }

    @Test
    fun `an unrecognised failure falls back to what it said about itself`() {
        assertEquals(
            "something else went wrong",
            webhookErrorLabel(IllegalStateException("something else went wrong"), target)
        )
    }
}
