package me.ashikoki.listea

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line that describes a webhook on a folder card, a list row and the List detail header.
 *
 * It carries two facts that have nothing to do with each other — whether the webhook is armed,
 * and what happened last time it fired — and the point of these is that neither ever hides the
 * other. A list that delivered and was then switched off still has a delivery worth reporting.
 */
class WebhookStatusLabelTest {

    private fun label(
        enabled: Boolean,
        status: String? = null,
        code: Int? = null
    ) = webhookStatusLabel(enabled, status, code)

    @Test
    fun `an armed webhook reports what it did last`() {
        assertEquals("Webhook · On · Never sent", label(enabled = true))
        assertEquals(
            "Webhook · On · Success",
            label(enabled = true, status = DeliveryStatus.SUCCESS.name)
        )
        assertEquals(
            "Webhook · On · Failed · HTTP 500",
            label(enabled = true, status = DeliveryStatus.FAILED.name, code = 500)
        )
    }

    @Test
    fun `switching a webhook off does not erase what it already sent`() {
        assertEquals(
            "Webhook · Off · Success",
            label(enabled = false, status = DeliveryStatus.SUCCESS.name)
        )
        assertEquals(
            "Webhook · Off · Failed · HTTP 500",
            label(enabled = false, status = DeliveryStatus.FAILED.name, code = 500)
        )
    }

    @Test
    fun `a webhook that was never armed and never fired says both`() {
        assertEquals("Webhook · Off · Never sent", label(enabled = false))
    }

    @Test
    fun `a failure with no status code still says it failed`() {
        assertEquals(
            "Webhook · On · Failed",
            label(enabled = true, status = DeliveryStatus.FAILED.name)
        )
    }
}
