package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the unsent history shows about a payload it kept.
 *
 * The keeping itself is a database write and is exercised on device; these pin the strings, which
 * are the part that has to agree with the dialog the user saw when the delivery failed. A record
 * whose reason reads differently from the notice that produced it would look like a second,
 * different failure.
 */
class UnsentHistoryTest {

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
    fun `the reason a record carries is the words the user was given`() {
        // A record's reason is the notice's outcome label verbatim, so these must stay readable
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
    fun `a record is named by the whole moment it failed, not just the time of day`() {
        // Records outlive the day they were made, so "16:40:05" would stop telling them apart.
        assertTrue(
            webhookTimestampLabel(0).matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""))
        )
    }
}
