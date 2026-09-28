package kliplayer

open class KlipException(
    val code: String,
    val fileName: String,
    val sourceLine: Int,
    detail: String,
) : RuntimeException("$code $fileName line $sourceLine: $detail")

class ParseError(fileName: String, sourceLine: Int, detail: String) :
    KlipException("KLP1001", fileName, sourceLine, detail)

class CompileError(code: String, fileName: String, sourceLine: Int, detail: String) :
    KlipException(code, fileName, sourceLine, detail)

data class Meta(
    val values: Map<String, String>,
    val addons: List<String> = emptyList(),
    val addonSourceLines: List<Int> = emptyList(),
) {
    val music: String? get() = values["music"]
    val width: Int get() = values["width"]?.toIntOrNull() ?: 160
    val height: Int get() = values["height"]?.toIntOrNull() ?: 40
}

data class Anchor(
    val name: String,
    val timeMs: Long,
    val bpm: Double,
    val sourceLine: Int,
)

data class KlipDocument(
    val fileName: String,
    val meta: Meta,
    val anchors: List<Anchor>,
    val cues: List<Cue>,
    val tracks: List<Track>,
)

data class Track(
    val name: String,
    val cursorId: String,
    val z: Int,
    val protect: Boolean,
    val sourceLine: Int,
    val entries: List<RawEvent>,
)

data class Cue(
    val name: String,
    val cursorId: String,
    val z: Int,
    val protect: Boolean,
    val sourceLine: Int,
    val entries: List<CueEntry>,
)

sealed interface CueEntry {
    val sourceLine: Int
}

data class RawEvent(
    override val sourceLine: Int,
    val timeExpr: String,
    val ops: List<Op>,
    val emitCue: String? = null,
) : CueEntry

data class LoopEntry(
    override val sourceLine: Int,
    val count: Int,
    val entries: List<RawEvent>,
) : CueEntry

sealed interface Op

data class Move(val row: Int, val col: Int) : Op
data class Foreground(val rgb: String?) : Op
data class Background(val rgb: String?) : Op
data class Style(val name: String?, val enabled: Boolean?) : Op
data class Text(val value: String) : Op
data class Space(val count: Int) : Op
data class FunctionCall(val name: String, val args: Map<String, String>) : Op
data object Newline : Op
data object CleanLine : Op
data object Clear : Op
data object HideCursor : Op
data object ShowCursor : Op

data class Event(
    val timeMs: Long,
    val order: Long,
    val cursorId: String,
    val z: Int,
    val protect: Boolean,
    val ops: List<Op>,
    val sourceLine: Int,
    val source: String,
)

data class Timeline(
    val document: KlipDocument,
    val events: List<Event>,
) {
    val startMs: Long get() = events.minOfOrNull { it.timeMs } ?: 0L
    val endMs: Long get() = events.maxOfOrNull { it.timeMs } ?: 0L
}

class ProtectionMask(
    val width: Int,
    val height: Int,
) {
    private val cells = IntArray(width * height) { UNPROTECTED }

    fun protectedAt(row: Int, col: Int): Int {
        if (!inside(row, col)) return UNPROTECTED
        return cells[index(row, col)]
    }

    fun canWrite(row: Int, col: Int, writerZ: Int): Boolean {
        if (!inside(row, col)) return false
        val protectedZ = protectedAt(row, col)
        return protectedZ == UNPROTECTED || writerZ >= protectedZ
    }

    fun canWriteCells(row: Int, col: Int, displayWidth: Int, writerZ: Int): Boolean {
        if (displayWidth <= 0) return true
        for (offset in 0 until displayWidth) {
            if (!canWrite(row, col + offset, writerZ)) return false
        }
        return true
    }

    fun mark(row: Int, col: Int, displayWidth: Int, writerZ: Int) {
        for (offset in 0 until displayWidth) {
            if (inside(row, col + offset)) {
                cells[index(row, col + offset)] = writerZ
            }
        }
    }

    fun clear(row: Int, col: Int, writerZ: Int): Boolean {
        if (!canWrite(row, col, writerZ)) return false
        cells[index(row, col)] = UNPROTECTED
        return true
    }

    private fun inside(row: Int, col: Int): Boolean =
        row in 1..height && col in 1..width

    private fun index(row: Int, col: Int): Int =
        (row - 1) * width + (col - 1)

    companion object {
        const val UNPROTECTED = -1
    }
}

fun Op.describe(): String =
    when (this) {
        is Move -> "mv $row,$col"
        is Foreground -> "color ${rgb ?: "default"}"
        is Background -> "background ${rgb ?: "default"}"
        is Style -> if (name == null) "style default" else "style $name ${if (enabled == true) "on" else "off"}"
        is Text -> "text ${value.replace("\n", "\\n")}"
        is Space -> "space $count"
        is FunctionCall -> "func $name"
        Newline -> "newline"
        CleanLine -> "cleanline"
        Clear -> "clear"
        HideCursor -> "hide"
        ShowCursor -> "show"
    }
