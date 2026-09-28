package kliplayer

import java.io.Flushable

class TerminalRenderer(
    private val width: Int,
    private val height: Int,
    private val out: Appendable = System.out,
    private val mask: ProtectionMask = ProtectionMask(width, height),
    private val synchronizedOutput: Boolean = true,
) {
    private val pending = StringBuilder()
    private val touchedCells = BooleanArray(width * height) { true }
    private val dirtyCells = BooleanArray(width * height) { true }
    private val cursors = mutableMapOf<String, CursorState>()
    private val eraseStyle = RenderStyle()
    private var physicalStyle: RenderStyle? = null
    private var minTouchedRow = if (width > 0 && height > 0) 1 else height + 1
    private var maxTouchedRow = if (width > 0 && height > 0) height else 0
    private var minTouchedCol = if (width > 0 && height > 0) 1 else width + 1
    private var maxTouchedCol = if (width > 0 && height > 0) width else 0

    @Synchronized
    fun render(event: Event) {
        val cursor = cursors.getOrPut(event.cursorId) { CursorState() }
        for (op in event.ops) {
            when (op) {
                is Move -> {
                    cursor.row = op.row
                    cursor.col = op.col
                }
                is Foreground -> cursor.style = cursor.style.copy(foregroundRgb = op.rgb)
                is Background -> cursor.style = cursor.style.copy(backgroundRgb = op.rgb)
                is Style -> cursor.style = cursor.style.apply(op)
                is Text -> writeText(cursor, op.value, event)
                is Space -> repeat(op.count) { writeCells(cursor, " ", 1, event) }
                is FunctionCall -> error("FunctionCall must be expanded before rendering")
                Newline -> {
                    cursor.row += 1
                    cursor.col = 1
                }
                CleanLine -> cleanLine(cursor.row, event.z)
                Clear -> clear(event.z)
                HideCursor -> pending.append("\u001b[?25l")
                ShowCursor -> pending.append("\u001b[?25h")
            }
        }
    }

    @Synchronized
    fun flush() {
        if (pending.isEmpty()) return
        if (synchronizedOutput) out.append(SYNC_UPDATE_START)
        out.append(pending)
        if (synchronizedOutput) out.append(SYNC_UPDATE_END)
        pending.clear()
        if (out is Flushable) out.flush()
    }

    @Synchronized
    fun restore() {
        flush()
        pending.append("\u001b[0m\u001b[39m\u001b[49m\u001b[?25h\n")
        physicalStyle = RenderStyle()
        flush()
    }

    private fun writeText(cursor: CursorState, value: String, event: Event) {
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val codePoint = iterator.nextInt()
            val displayWidth = WcWidth.ofCodePoint(codePoint)
            if (displayWidth <= 0) continue
            writeCells(cursor, String(Character.toChars(codePoint)), displayWidth, event)
        }
    }

    private fun writeCells(cursor: CursorState, text: String, displayWidth: Int, event: Event) {
        if (mask.canWriteCells(cursor.row, cursor.col, displayWidth, event.z)) {
            ensurePhysicalStyle(cursor.style)
            movePhysical(cursor.row, cursor.col)
            pending.append(text)
            markTouched(cursor.row, cursor.col, displayWidth, text, cursor.style)
            if (event.protect) {
                mask.mark(cursor.row, cursor.col, displayWidth, event.z)
            }
        }
        cursor.col += displayWidth
    }

    private fun cleanLine(row: Int, writerZ: Int) {
        if (row !in 1..height || !hasTouchedCells()) return
        clearTouchedCells(row, row, 1, width, writerZ)
    }

    private fun clear(writerZ: Int) {
        if (!hasTouchedCells()) return
        clearTouchedCells(minTouchedRow, maxTouchedRow, minTouchedCol, maxTouchedCol, writerZ)
    }

    private fun clearTouchedCells(
        rowStart: Int,
        rowEnd: Int,
        colStart: Int,
        colEnd: Int,
        writerZ: Int,
    ) {
        var changed = false
        for (row in rowStart..rowEnd) {
            var runStart = 0
            var lastDirtyCol = 0

            fun flushRun() {
                if (runStart != 0 && lastDirtyCol != 0) {
                    eraseRun(row, runStart, lastDirtyCol - runStart + 1)
                }
                runStart = 0
                lastDirtyCol = 0
            }

            for (col in colStart..colEnd) {
                val index = cellIndex(row, col)
                if (!touchedCells[index]) {
                    flushRun()
                    continue
                }

                if (mask.clear(row, col, writerZ)) {
                    val wasDirty = dirtyCells[index]
                    touchedCells[index] = false
                    dirtyCells[index] = false
                    changed = true
                    if (wasDirty) {
                        if (runStart == 0) runStart = col
                        lastDirtyCol = col
                    }
                } else {
                    flushRun()
                }
            }
            flushRun()
        }
        if (changed) recomputeTouchedBounds()
    }

    private fun eraseRun(row: Int, col: Int, count: Int) {
        ensurePhysicalStyle(eraseStyle)
        movePhysical(row, col)
        repeat(count) { pending.append(' ') }
    }

    private fun markTouched(row: Int, col: Int, displayWidth: Int, text: String, style: RenderStyle) {
        val isDirty = text != " " || style != eraseStyle
        for (offset in 0 until displayWidth) {
            val cellCol = col + offset
            if (!inside(cellCol = cellCol, row = row)) continue
            val index = cellIndex(row, cellCol)
            touchedCells[index] = true
            dirtyCells[index] = isDirty
            expandTouchedBounds(row, cellCol)
        }
    }

    private fun expandTouchedBounds(row: Int, col: Int) {
        if (row < minTouchedRow) minTouchedRow = row
        if (row > maxTouchedRow) maxTouchedRow = row
        if (col < minTouchedCol) minTouchedCol = col
        if (col > maxTouchedCol) maxTouchedCol = col
    }

    private fun recomputeTouchedBounds() {
        minTouchedRow = height + 1
        maxTouchedRow = 0
        minTouchedCol = width + 1
        maxTouchedCol = 0
        for (row in 1..height) {
            for (col in 1..width) {
                if (touchedCells[cellIndex(row, col)]) {
                    expandTouchedBounds(row, col)
                }
            }
        }
    }

    private fun hasTouchedCells(): Boolean =
        maxTouchedRow != 0

    private fun inside(row: Int, cellCol: Int): Boolean =
        row in 1..height && cellCol in 1..width

    private fun cellIndex(row: Int, col: Int): Int =
        (row - 1) * width + (col - 1)

    private fun movePhysical(row: Int, col: Int) {
        if (row in 1..height && col in 1..width) {
            pending.append("\u001b[").append(row.toString()).append(';').append(col.toString()).append('H')
        }
    }

    private fun ensurePhysicalStyle(target: RenderStyle) {
        val current = physicalStyle
        if (current == target) return

        if (current == null || current.foregroundRgb != target.foregroundRgb) {
            pending.append(if (target.foregroundRgb == null) "\u001b[39m" else rgbAnsi(38, target.foregroundRgb))
        }
        if (current == null || current.backgroundRgb != target.backgroundRgb) {
            pending.append(if (target.backgroundRgb == null) "\u001b[49m" else rgbAnsi(48, target.backgroundRgb))
        }
        if (current == null || current.bold != target.bold) {
            sgr(if (target.bold) 1 else 22)
        }
        if (current == null || current.italic != target.italic) {
            sgr(if (target.italic) 3 else 23)
        }
        if (current == null || current.underline != target.underline) {
            sgr(if (target.underline) 4 else 24)
        }
        if (current == null || current.strikeline != target.strikeline) {
            sgr(if (target.strikeline) 9 else 29)
        }
        physicalStyle = target
    }

    private fun sgr(code: Int) {
        pending.append("\u001b[").append(code.toString()).append('m')
    }

    private fun rgbAnsi(prefix: Int, rgb: String): String {
        val r = rgb.substring(0, 2).toInt(16)
        val g = rgb.substring(2, 4).toInt(16)
        val b = rgb.substring(4, 6).toInt(16)
        return "\u001b[$prefix;2;$r;$g;${b}m"
    }

    private data class CursorState(
        var row: Int = 1,
        var col: Int = 1,
        var style: RenderStyle = RenderStyle(),
    )

    private data class RenderStyle(
        val foregroundRgb: String? = null,
        val backgroundRgb: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikeline: Boolean = false,
    ) {
        fun apply(style: Style): RenderStyle =
            when (style.name) {
                null -> copy(bold = false, italic = false, underline = false, strikeline = false)
                "bold" -> copy(bold = style.enabled == true)
                "italic" -> copy(italic = style.enabled == true)
                "underline" -> copy(underline = style.enabled == true)
                "strikeline" -> copy(strikeline = style.enabled == true)
                else -> this
            }
    }

    private companion object {
        const val SYNC_UPDATE_START = "\u001b[?2026h"
        const val SYNC_UPDATE_END = "\u001b[?2026l"
    }
}
