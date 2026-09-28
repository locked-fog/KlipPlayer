package kliplayer

import java.nio.file.Files
import java.nio.file.Path

class KlipParser(private val fileName: String) {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val color = Regex("[0-9A-Fa-f]{6}")

    fun parse(text: String): KlipDocument {
        val meta = linkedMapOf<String, String>()
        val addons = mutableListOf<String>()
        val addonSourceLines = mutableListOf<Int>()
        val anchors = mutableListOf<Anchor>()
        val cues = mutableListOf<Cue>()
        val tracks = mutableListOf<Track>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()

        var block: BlockBuilder? = null
        var loop: LoopBuilder? = null

        for ((index, originalLine) in lines.withIndex()) {
            val lineNo = index + 1
            val line = originalLine.trimStart()
            val controlLine = line.trim()
            if (line.isBlank() || line.startsWith("//")) continue

            val currentBlock = block
            if (currentBlock == null) {
                val tag = firstTag(line, lineNo)
                when (val name = splitFields(tag.content, lineNo).firstOrNull()) {
                    "meta" -> {
                        ensureNoRest(tag.rest, lineNo)
                        val parsed = parseMeta(tag.content, lineNo)
                        meta.putAll(parsed.values)
                        addons += parsed.addons
                        repeat(parsed.addons.size) { addonSourceLines += lineNo }
                    }
                    "anchor" -> {
                        ensureNoRest(tag.rest, lineNo)
                        anchors += parseAnchor(tag.content, lineNo)
                    }
                    "track" -> {
                        ensureNoRest(tag.rest, lineNo)
                        block = parseBlockHeader(tag.content, lineNo, isCue = false)
                    }
                    "cue" -> {
                        ensureNoRest(tag.rest, lineNo)
                        block = parseBlockHeader(tag.content, lineNo, isCue = true)
                    }
                    else -> parseError(lineNo, "未知顶层标签 [$name]")
                }
                continue
            }

            if (currentBlock.isCue) {
                when {
                    controlLine == "[endcue]" -> {
                        if (loop != null) parseError(lineNo, "cue 结束前缺少 [endloop]")
                        cues += currentBlock.toCue()
                        block = null
                    }
                    controlLine == "[endtrack]" -> parseError(lineNo, "[endtrack] 出现在 cue 内")
                    controlLine.startsWith("[loop ") -> {
                        if (loop != null) parseError(lineNo, "不允许嵌套 loop")
                        loop = parseLoopHeader(controlLine, lineNo)
                    }
                    controlLine == "[endloop]" -> {
                        val done = loop ?: parseError(lineNo, "[endloop] 没有对应的 [loop]")
                        currentBlock.entries += done.toEntry()
                        loop = null
                    }
                    else -> {
                        val event = parseEventLine(line, lineNo, allowEmit = false)
                        val currentLoop = loop
                        if (currentLoop != null) currentLoop.entries += event else currentBlock.entries += event
                    }
                }
            } else {
                when {
                    controlLine == "[endtrack]" -> {
                        tracks += currentBlock.toTrack()
                        block = null
                    }
                    controlLine.startsWith("[loop ") || controlLine == "[endloop]" -> parseError(lineNo, "loop 只允许出现在 cue 内")
                    controlLine == "[endcue]" -> parseError(lineNo, "[endcue] 出现在 track 内")
                    else -> currentBlock.entries += parseEventLine(line, lineNo, allowEmit = true)
                }
            }
        }

        block?.let { parseError(it.sourceLine, "块 [${if (it.isCue) "cue" else "track"} ${it.name}] 未关闭") }
        return KlipDocument(fileName, Meta(meta, addons, addonSourceLines), anchors, cues, tracks)
    }

    private fun parseMeta(content: String, lineNo: Int): ParsedMeta {
        val fields = splitFields(content, lineNo)
        if (fields.size < 2) parseError(lineNo, "meta 缺少 key=value")
        val values = linkedMapOf<String, String>()
        val addons = mutableListOf<String>()
        for (field in fields.drop(1)) {
            val separator = field.indexOf('=')
            if (separator <= 0) parseError(lineNo, "meta 参数不是 key=value: $field")
            val key = field.substring(0, separator)
            val value = field.substring(separator + 1)
            when (key) {
                "width", "height" -> {
                    val parsed = value.toIntOrNull() ?: parseError(lineNo, "meta $key 必须是正整数")
                    if (parsed <= 0) parseError(lineNo, "meta $key 必须是正整数")
                }
                "music", "title" -> Unit
                "addon" -> if (value.isEmpty()) parseError(lineNo, "meta addon 不能为空")
                else -> if (!identifier.matches(key)) parseError(lineNo, "非法 meta key: $key")
            }
            if (key == "addon") {
                addons += value
            } else {
                values[key] = value
            }
        }
        return ParsedMeta(values, addons)
    }

    private fun parseAnchor(content: String, lineNo: Int): Anchor {
        val fields = splitFields(content, lineNo)
        if (fields.size != 4) parseError(lineNo, "anchor 语法应为 [anchor name mm:ss.mmm bpm=number]")
        val name = fields[1]
        validateIdentifier(name, lineNo)
        val timeMs = parseAbsoluteTime(fields[2], lineNo)
        val bpmField = fields[3]
        if (!bpmField.startsWith("bpm=")) parseError(lineNo, "anchor 缺少 bpm=number")
        val bpm = bpmField.removePrefix("bpm=").toDoubleOrNull()
            ?: parseError(lineNo, "BPM 无法解析: ${bpmField.removePrefix("bpm=")}")
        if (bpm <= 0.0) parseError(lineNo, "BPM 必须为正数")
        return Anchor(name, timeMs, bpm, lineNo)
    }

    private fun parseBlockHeader(content: String, lineNo: Int, isCue: Boolean): BlockBuilder {
        val fields = splitFields(content, lineNo)
        if (fields.size < 2) parseError(lineNo, "track/cue 缺少名称")
        val name = fields[1]
        validateIdentifier(name, lineNo)
        val attrs = parseAttrs(fields.drop(2), lineNo, allowed = setOf("cursor", "z", "protect"))
        val cursor = attrs["cursor"] ?: name
        validateIdentifier(cursor, lineNo)
        val z = attrs["z"]?.toIntOrNull()
            ?: if ("z" in attrs) parseError(lineNo, "z 必须是整数") else 0
        if (z < 0) parseError(lineNo, "z 必须是非负整数")
        val protect = when (attrs["protect"] ?: "off") {
            "on" -> true
            "off" -> false
            else -> parseError(lineNo, "protect 必须是 on 或 off")
        }
        return BlockBuilder(name, cursor, z, protect, lineNo, isCue)
    }

    private fun parseAttrs(fields: List<String>, lineNo: Int, allowed: Set<String>): Map<String, String> {
        val attrs = linkedMapOf<String, String>()
        for (field in fields) {
            val separator = field.indexOf('=')
            if (separator <= 0) parseError(lineNo, "参数不是 key=value: $field")
            val key = field.substring(0, separator)
            val value = field.substring(separator + 1)
            if (key !in allowed) parseError(lineNo, "未知参数: $key")
            if (attrs.containsKey(key)) parseError(lineNo, "重复参数: $key")
            if (value.isEmpty()) parseError(lineNo, "参数值不能为空: $key")
            attrs[key] = value
        }
        return attrs
    }

    private fun parseLoopHeader(line: String, lineNo: Int): LoopBuilder {
        val tag = firstTag(line, lineNo)
        ensureNoRest(tag.rest, lineNo)
        val fields = splitFields(tag.content, lineNo)
        if (fields.size != 2 || fields[0] != "loop") parseError(lineNo, "loop 语法应为 [loop n]")
        val count = fields[1].toIntOrNull() ?: parseError(lineNo, "loop 次数必须是正整数")
        if (count <= 0) parseError(lineNo, "loop 次数必须是正整数")
        return LoopBuilder(lineNo, count)
    }

    private fun parseEventLine(line: String, lineNo: Int, allowEmit: Boolean): RawEvent {
        val tag = firstTag(line, lineNo)
        val body = parseOps(tag.rest, lineNo)
        if (body.ops.any { it is FunctionCall }) {
            if (body.emitCue != null || body.ops.size != 1) {
                parseError(lineNo, "func 行不能混写其它命令、文本或 emit")
            }
        }
        if (body.emitCue != null) {
            if (!allowEmit) parseError(lineNo, "cue 内不允许使用 emit")
            if (body.ops.isNotEmpty()) parseError(lineNo, "emit 行不能混写其它命令或文本")
        }
        return RawEvent(lineNo, tag.content.trim(), body.ops, body.emitCue)
    }

    private fun parseOps(input: String, lineNo: Int): ParsedBody {
        val ops = mutableListOf<Op>()
        var emitCue: String? = null
        var index = 0
        while (index < input.length) {
            val tagStart = findNextTagStart(input, index)
            if (tagStart < 0) {
                addTextOp(input.substring(index), ops)
                break
            }
            if (tagStart > index) {
                addTextOp(input.substring(index, tagStart), ops)
            }
            val tag = readTag(input, tagStart, lineNo)
            val parsed = parseCommandTag(tag.content.trim(), lineNo)
            if (parsed.emitCue != null) {
                if (emitCue != null) parseError(lineNo, "同一事件不能包含多个 emit")
                emitCue = parsed.emitCue
            } else {
                ops += parsed.ops
            }
            index = tag.nextIndex
        }
        return ParsedBody(ops, emitCue)
    }

    private fun addTextOp(raw: String, ops: MutableList<Op>) {
        if (raw.isBlank()) return
        ops += Text(unescapeText(raw))
    }

    private fun parseCommandTag(content: String, lineNo: Int): ParsedBody {
        val fields = splitFields(content, lineNo)
        val command = fields.firstOrNull() ?: parseError(lineNo, "空标签")
        return when (command) {
            "emit" -> {
                if (fields.size != 2) parseError(lineNo, "emit 语法应为 [emit cueName]")
                validateIdentifier(fields[1], lineNo)
                ParsedBody(emptyList(), fields[1])
            }
            "func" -> ParsedBody(listOf(parseFunctionCall(fields, lineNo)), null)
            "mv" -> {
                if (fields.size != 2) parseError(lineNo, "mv 语法应为 [mv row,col]")
                val parts = fields[1].split(',')
                if (parts.size != 2) parseError(lineNo, "mv 必须使用逗号: [mv row,col]")
                val row = parts[0].toIntOrNull() ?: parseError(lineNo, "mv row 不是整数")
                val col = parts[1].toIntOrNull() ?: parseError(lineNo, "mv col 不是整数")
                if (row <= 0 || col <= 0) parseError(lineNo, "mv row 和 col 必须从 1 开始")
                ParsedBody(listOf(Move(row, col)), null)
            }
            "color" -> ParsedBody(listOf(Foreground(parseColorArg(fields, lineNo))), null)
            "background" -> ParsedBody(listOf(Background(parseColorArg(fields, lineNo))), null)
            "style" -> ParsedBody(listOf(parseStyle(fields, lineNo)), null)
            "space" -> {
                if (fields.size > 2) parseError(lineNo, "space 语法应为 [space] 或 [space n]")
                val count = if (fields.size == 1) 1 else fields.getOrNull(1)?.toIntOrNull()
                    ?: parseError(lineNo, "space 数量必须是整数")
                if (count < 0) parseError(lineNo, "space 数量不能为负数")
                ParsedBody(listOf(Space(count)), null)
            }
            "newline" -> {
                ensureArgCount(fields, 1, lineNo, "newline")
                ParsedBody(listOf(Newline), null)
            }
            "cleanline" -> {
                ensureArgCount(fields, 1, lineNo, "cleanline")
                ParsedBody(listOf(CleanLine), null)
            }
            "clear" -> {
                ensureArgCount(fields, 1, lineNo, "clear")
                ParsedBody(listOf(Clear), null)
            }
            "hide" -> {
                ensureArgCount(fields, 1, lineNo, "hide")
                ParsedBody(listOf(HideCursor), null)
            }
            "show" -> {
                ensureArgCount(fields, 1, lineNo, "show")
                ParsedBody(listOf(ShowCursor), null)
            }
            else -> parseError(lineNo, "未知命令标签 [$command]")
        }
    }

    private fun parseFunctionCall(fields: List<String>, lineNo: Int): FunctionCall {
        if (fields.size < 2) parseError(lineNo, "func 语法应为 [func name key=value ...]")
        val name = fields[1]
        validateIdentifier(name, lineNo)
        val args = linkedMapOf<String, String>()
        for (field in fields.drop(2)) {
            val separator = field.indexOf('=')
            if (separator <= 0) parseError(lineNo, "func 参数不是 key=value: $field")
            val key = field.substring(0, separator)
            val value = field.substring(separator + 1)
            validateIdentifier(key, lineNo)
            if (args.containsKey(key)) parseError(lineNo, "重复参数: $key")
            if (value.isEmpty()) parseError(lineNo, "参数值不能为空: $key")
            args[key] = value
        }
        return FunctionCall(name, args)
    }

    private fun parseColorArg(fields: List<String>, lineNo: Int): String? {
        if (fields.size != 2) parseError(lineNo, "${fields.first()} 语法应为 [${fields.first()} rrggbb|default]")
        val value = fields[1]
        if (value == "default") return null
        if (!color.matches(value)) parseError(lineNo, "颜色必须是 6 位十六进制 RGB: $value")
        return value.lowercase()
    }

    private fun parseStyle(fields: List<String>, lineNo: Int): Style {
        if (fields.size == 2 && fields[1] == "default") return Style(null, null)
        if (fields.size != 3) parseError(lineNo, "style 语法应为 [style name on|off] 或 [style default]")
        if (fields[1] !in setOf("bold", "italic", "underline", "strikeline")) {
            parseError(lineNo, "未知 style: ${fields[1]}")
        }
        val enabled = when (fields[2]) {
            "on" -> true
            "off" -> false
            else -> parseError(lineNo, "style 开关必须是 on 或 off")
        }
        return Style(fields[1], enabled)
    }

    private fun parseAbsoluteTime(value: String, lineNo: Int): Long {
        val match = ABSOLUTE_TIME.matchEntire(value) ?: parseError(lineNo, "绝对时间无法解析: $value")
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val millis = match.groupValues[3].toLong()
        if (seconds > 59) parseError(lineNo, "秒必须在 00..59: $value")
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun firstTag(line: String, lineNo: Int): FirstTag {
        if (!line.startsWith("[")) parseError(lineNo, "事件行必须以标签开头")
        val read = readTag(line, 0, lineNo)
        return FirstTag(read.content, line.substring(read.nextIndex))
    }

    private fun readTag(input: String, start: Int, lineNo: Int): TagRead {
        var index = start + 1
        while (index < input.length) {
            if (input[index] == ']') {
                return TagRead(input.substring(start + 1, index), index + 1)
            }
            index++
        }
        parseError(lineNo, "标签缺少右方括号")
    }

    private fun findNextTagStart(input: String, start: Int): Int {
        var index = start
        while (index < input.length) {
            if (input[index] == '[' && !isEscaped(input, index)) return index
            index++
        }
        return -1
    }

    private fun isEscaped(input: String, index: Int): Boolean {
        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && input[cursor] == '\\') {
            slashCount++
            cursor--
        }
        return slashCount % 2 == 1
    }

    private fun splitFields(content: String, lineNo: Int): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        for (ch in content) {
            when {
                escaped -> {
                    current.append(
                        when (ch) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> ch
                        },
                    )
                    escaped = false
                }
                ch == '\\' && quoted -> escaped = true
                ch == '"' -> quoted = !quoted
                ch.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (escaped) parseError(lineNo, "字符串转义不完整")
        if (quoted) parseError(lineNo, "字符串缺少右引号")
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun unescapeText(raw: String): String {
        val out = StringBuilder()
        var escaped = false
        for (ch in raw) {
            if (escaped) {
                out.append(
                    when (ch) {
                        '[' -> '['
                        ']' -> ']'
                        '\\' -> '\\'
                        'n' -> '\n'
                        't' -> '\t'
                        else -> ch
                    },
                )
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else {
                out.append(ch)
            }
        }
        if (escaped) out.append('\\')
        return out.toString()
    }

    private fun validateIdentifier(value: String, lineNo: Int) {
        if (!identifier.matches(value)) parseError(lineNo, "非法标识符: $value")
    }

    private fun ensureNoRest(rest: String, lineNo: Int) {
        if (rest.isNotBlank()) parseError(lineNo, "顶层/块标签后不允许额外文本")
    }

    private fun ensureArgCount(fields: List<String>, expected: Int, lineNo: Int, command: String) {
        if (fields.size != expected) parseError(lineNo, "$command 不接受参数")
    }

    private fun parseError(lineNo: Int, detail: String): Nothing =
        throw ParseError(fileName, lineNo, detail)

    private data class TagRead(val content: String, val nextIndex: Int)

    private data class ParsedMeta(val values: Map<String, String>, val addons: List<String>)

    private data class ParsedBody(val ops: List<Op>, val emitCue: String?)

    private class BlockBuilder(
        val name: String,
        val cursorId: String,
        val z: Int,
        val protect: Boolean,
        val sourceLine: Int,
        val isCue: Boolean,
    ) {
        val entries = mutableListOf<CueEntry>()

        fun toCue(): Cue =
            Cue(name, cursorId, z, protect, sourceLine, entries.toList())

        fun toTrack(): Track =
            Track(name, cursorId, z, protect, sourceLine, entries.filterIsInstance<RawEvent>())
    }

    private class LoopBuilder(val sourceLine: Int, val count: Int) {
        val entries = mutableListOf<RawEvent>()

        fun toEntry(): LoopEntry = LoopEntry(sourceLine, count, entries.toList())
    }

    private data class FirstTag(val content: String, val rest: String)

    companion object {
        private val ABSOLUTE_TIME = Regex("(\\d+):(\\d{2})\\.(\\d{3})")

        fun parse(path: Path): KlipDocument =
            KlipParser(path.toString()).parse(Files.readString(path))

        fun parseText(text: String, fileName: String = "<memory>"): KlipDocument =
            KlipParser(fileName).parse(text)
    }
}
