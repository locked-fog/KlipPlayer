package kliplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectionMaskTest {
    @Test
    fun `lower z cannot overwrite higher protected cell`() {
        val mask = ProtectionMask(width = 10, height = 3)

        assertTrue(mask.canWrite(1, 1, writerZ = 20))
        mask.mark(1, 1, displayWidth = 2, writerZ = 100)

        assertFalse(mask.canWriteCells(1, 1, displayWidth = 2, writerZ = 20))
        assertTrue(mask.canWriteCells(1, 1, displayWidth = 2, writerZ = 100))
        assertTrue(mask.canWriteCells(1, 1, displayWidth = 2, writerZ = 120))
        assertEquals(100, mask.protectedAt(1, 2))
    }

    @Test
    fun `clear follows z permission`() {
        val mask = ProtectionMask(width = 4, height = 1)
        mask.mark(1, 1, displayWidth = 1, writerZ = 80)

        assertFalse(mask.clear(1, 1, writerZ = 20))
        assertEquals(80, mask.protectedAt(1, 1))
        assertTrue(mask.clear(1, 1, writerZ = 80))
        assertEquals(ProtectionMask.UNPROTECTED, mask.protectedAt(1, 1))
    }
}
