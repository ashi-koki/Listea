package me.ashikoki.listea

import me.ashikoki.listea.data.ItemAction
import me.ashikoki.listea.data.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The display side of configurable actions. The wire side lives in [WebhookPayloadTest]; the
 * point of both is that a slot's identity never depends on either name.
 */
class SettingsTest {

    private fun item(favorite: Boolean = false, custom1: Boolean = false, custom2: Boolean = false) =
        ListItemEntity(
            id = 1,
            listId = 1,
            title = "a.jpg",
            sortOrder = 0,
            createdAt = 0,
            isFavorite = favorite,
            custom1 = custom1,
            custom2 = custom2
        )

    @Test
    fun `defaults are the factory labels and values`() {
        val settings = AppSettings()
        assertEquals("C1", settings.labelOf(ItemAction.CUSTOM1))
        assertEquals("C2", settings.labelOf(ItemAction.CUSTOM2))
        assertEquals("cust1", settings.wireOf(ItemAction.CUSTOM1))
        assertEquals("cust2", settings.wireOf(ItemAction.CUSTOM2))
    }

    @Test
    fun `configured display names are what the review chips show`() {
        val settings = AppSettings(custom1DisplayName = "Save", custom2DisplayName = "Later")
        assertEquals("Save", settings.labelOf(ItemAction.CUSTOM1))
        assertEquals("Later", settings.labelOf(ItemAction.CUSTOM2))
    }

    @Test
    fun `favorite keeps its own label and value whatever the slots are called`() {
        val settings = AppSettings(custom1DisplayName = "Favorite", custom1WebhookValue = "favorite")
        assertEquals("★", settings.labelOf(ItemAction.FAVORITE))
        assertEquals("favorite", settings.wireOf(ItemAction.FAVORITE))
    }

    @Test
    fun `display name and webhook value are independent of each other`() {
        val settings = AppSettings(custom1DisplayName = "Save", custom1WebhookValue = "tag1")
        assertEquals("Save", settings.labelOf(ItemAction.CUSTOM1))
        assertEquals("tag1", settings.wireOf(ItemAction.CUSTOM1))
    }

    @Test
    fun `the row summary follows the configured names`() {
        val marked = item(favorite = true, custom1 = true)
        assertEquals("★ · C1", itemActionLabel(marked, AppSettings()))
        assertEquals(
            "★ · Save",
            itemActionLabel(marked, AppSettings(custom1DisplayName = "Save"))
        )
    }

    @Test
    fun `an item with no actions has no summary`() {
        assertNull(itemActionLabel(item(), AppSettings()))
        assertNotNull(itemActionLabel(item(custom2 = true), AppSettings()))
    }

    @Test
    fun `blank settings text is rejected, ordinary values are not`() {
        assertNotNull(settingsTextError(""))
        assertNotNull(settingsTextError("   "))
        assertNull(settingsTextError("tag1"))
        assertNull(settingsTextError("favorite-source"))
        assertNull(settingsTextError("archive"))
    }
}
