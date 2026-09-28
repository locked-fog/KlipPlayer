package kliplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class WcWidthTest {
    @Test
    fun `cjk kana fullwidth and combining widths`() {
        assertEquals(2, WcWidth.displayWidth("熱"))
        assertEquals(2, WcWidth.displayWidth("異"))
        assertEquals(2, WcWidth.displayWidth("常"))
        assertEquals(2, WcWidth.displayWidth("あ"))
        assertEquals(2, WcWidth.displayWidth("ア"))
        assertEquals(1, WcWidth.displayWidth("A"))
        assertEquals(2, WcWidth.displayWidth("，"))
        assertEquals(2, WcWidth.displayWidth("。"))
        assertEquals(0, WcWidth.displayWidth("\u0301"))
    }
}
