package me.ashikoki.listea

import me.ashikoki.listea.data.FolderDiff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freshness is derived straight from [FolderDiff.hasChanges], so these lock down the rule the
 * whole feature rests on: only *unacknowledged* changes make a list stale.
 */
class SourceFreshnessTest {

    private fun diff(
        added: List<String> = emptyList(),
        missing: List<String> = emptyList(),
        restored: List<String> = emptyList(),
        unchanged: Int = 0,
        newlyMissing: Int = 0
    ) = FolderDiff(added, missing, restored, unchanged, newlyMissing)

    @Test
    fun `an unchanged folder is up to date`() {
        assertFalse(diff(unchanged = 12).hasChanges)
    }

    @Test
    fun `a file that was already acknowledged as missing is not news`() {
        // Reconciled earlier, the user chose to keep the item, and the file is still absent.
        // It shows up as a missing path every scan from now on, and must never make the list
        // permanently stale.
        assertFalse(diff(missing = listOf("gone.jpg"), unchanged = 5, newlyMissing = 0).hasChanges)
    }

    @Test
    fun `a newly vanished file is a real change`() {
        assertTrue(diff(missing = listOf("gone.jpg"), unchanged = 5, newlyMissing = 1).hasChanges)
    }

    @Test
    fun `added and restored files are real changes`() {
        assertTrue(diff(added = listOf("new.jpg")).hasChanges)
        assertTrue(diff(restored = listOf("back.jpg")).hasChanges)
    }

    @Test
    fun `a new file alongside an acknowledged missing one still counts`() {
        assertTrue(diff(added = listOf("new.jpg"), missing = listOf("gone.jpg")).hasChanges)
    }
}
