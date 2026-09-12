package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the webhook history shows about a delivery it kept, and what the two dialogs around a
 * delivery say about one.
 *
 * The keeping itself is a database write and is exercised on device; these pin the strings, which
 * are the part that has to agree across three places — the question asked before a send, the
 * result shown after it, and the record left behind. A record whose wording reads differently
 * from the notice that produced it would look like a second, different event.
 */
class WebhookHistoryTest {

    private fun notice(event: String, outcome: NoticeOutcome, defaultWebhook: Boolean = false) =
        WebhookNotice(
            event = event,
            listTitle = "2026-07-11",
            host = "example.com",
            itemCount = 4,
            outcome = outcome,
            httpCode = if (outcome == NoticeOutcome.SENT) 200 else 500,
            error = null,
            defaultWebhook = defaultWebhook,
            at = 0
        )

    private fun confirmation(defaultWebhook: Boolean) = WebhookConfirmation(
        event = EVENT_REVIEW_EXITED,
        listTitle = "2026-07-11",
        host = "example.com",
        itemCount = 4,
        defaultWebhook = defaultWebhook
    )

    @Test
    fun `an event is named the same live and in the history`() {
        listOf(
            EVENT_LIST_COMPLETED,
            EVENT_WEBHOOK_TEST,
            EVENT_QUICK_REVIEW_COMPLETED,
            EVENT_REVIEW_EXITED
        ).forEach { event ->
            assertEquals(
                notice(event, NoticeOutcome.FAILED).eventLabel,
                webhookEventLabel(event)
            )
        }
    }

    @Test
    fun `an event from a future version is shown rather than swallowed`() {
        assertEquals("list.something.new", webhookEventLabel("list.something.new"))
    }

    @Test
    fun `the detail a record carries is the words the user was given`() {
        // A record's detail is the notice's outcome label verbatim, so these must stay readable
        // on their own, away from the dialog that first said them.
        assertEquals("Failed · HTTP 500", notice(EVENT_REVIEW_EXITED, NoticeOutcome.FAILED).outcomeLabel)
        assertEquals(
            "Nothing was sent · the default webhook is switched off",
            notice(EVENT_QUICK_REVIEW_COMPLETED, NoticeOutcome.DISABLED, defaultWebhook = true)
                .outcomeLabel
        )
        assertEquals(
            "Nothing was sent · this list's webhook is switched off",
            notice(EVENT_LIST_COMPLETED, NoticeOutcome.DISABLED).outcomeLabel
        )
    }

    @Test
    fun `a successful delivery is kept in words of its own`() {
        // The history holds successes now, so "Sent" has to read as a verdict rather than as the
        // absence of a failure.
        val sent = notice(EVENT_LIST_COMPLETED, NoticeOutcome.SENT)

        assertEquals("Webhook sent", sent.headline)
        assertEquals("Success · HTTP 200", sent.outcomeLabel)
    }

    @Test
    fun `a declined delivery reads as a choice, not as a failure`() {
        val declined = notice(EVENT_REVIEW_EXITED, NoticeOutcome.DECLINED)

        assertEquals("Not sent", declined.headline)
        assertEquals("Nothing was sent · you chose not to send it", declined.outcomeLabel)
        // Nothing left the device, so it must not be counted as an attempt — that is what stops
        // it being recorded against the list as a delivery.
        assertFalse(declined.attempted)
    }

    @Test
    fun `every outcome has something to say, including ones added later`() {
        NoticeOutcome.entries.forEach { outcome ->
            val described = notice(EVENT_LIST_COMPLETED, outcome)
            assertTrue(outcome.name, described.headline.isNotBlank())
            assertTrue(outcome.name, described.outcomeLabel.isNotBlank())
        }
    }

    @Test
    fun `the confirmation names the webhook the payload would actually go to`() {
        // Quick Review posts through the app default and a List through its own, and the question
        // has to say which — "send this?" is not answerable without knowing where.
        assertEquals("the default webhook", confirmation(defaultWebhook = true).destinationLabel)
        assertEquals("this list's webhook", confirmation(defaultWebhook = false).destinationLabel)
    }

    @Test
    fun `the confirmation sizes the payload the same way the progress dialog does`() {
        val progress = WebhookProgress(
            event = EVENT_REVIEW_EXITED,
            listTitle = "2026-07-11",
            host = "example.com",
            itemCount = 4,
            defaultWebhook = false
        )

        assertEquals(progress.summaryLabel, confirmation(defaultWebhook = false).summaryLabel)
        assertEquals("Review left - 4 items - example.com", progress.summaryLabel)
    }

    @Test
    fun `a payload with one item is not described in the plural`() {
        assertEquals("1 item", countOfItems(1))
        assertEquals("0 items", countOfItems(0))
        assertEquals("42 items", countOfItems(42))
    }

    @Test
    fun `a record is named by the whole moment it happened, not just the time of day`() {
        // Records outlive the day they were made, so "16:40:05" would stop telling them apart.
        assertTrue(
            webhookTimestampLabel(0).matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""))
        )
    }
}
