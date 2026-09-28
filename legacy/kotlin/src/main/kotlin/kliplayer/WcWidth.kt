package kliplayer

object WcWidth {
    fun displayWidth(text: String): Int {
        var width = 0
        val codePoints = text.codePoints().iterator()
        while (codePoints.hasNext()) {
            width += ofCodePoint(codePoints.nextInt())
        }
        return width
    }

    fun ofCodePoint(codePoint: Int): Int {
        if (codePoint == 0) return 0
        if (codePoint < 32 || codePoint in 0x7f..0x9f) return 0
        if (isCombining(codePoint)) return 0
        if (isWide(codePoint) || isEmoji(codePoint)) return 2
        return 1
    }

    private fun isCombining(codePoint: Int): Boolean =
        codePoint in 0x0300..0x036f ||
            codePoint in 0x1ab0..0x1aff ||
            codePoint in 0x1dc0..0x1dff ||
            codePoint in 0x20d0..0x20ff ||
            codePoint in 0xfe20..0xfe2f

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115f ||
            codePoint in 0x2329..0x232a ||
            codePoint in 0x2e80..0xa4cf ||
            codePoint in 0xac00..0xd7a3 ||
            codePoint in 0xf900..0xfaff ||
            codePoint in 0xfe10..0xfe19 ||
            codePoint in 0xfe30..0xfe6f ||
            codePoint in 0xff00..0xff60 ||
            codePoint in 0xffe0..0xffe6 ||
            codePoint in 0x20000..0x3fffd

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1f000..0x1faff
}
