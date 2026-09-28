package kliplayer

import kotlin.math.roundToLong

class KlipCompiler {
    fun compile(document: KlipDocument): Timeline {
        val addons = LuaAddonRegistry.load(document)
        val anchors = anchorsByName(document)
        val cues = cuesByName(document)
        var order = 0L
        fun nextOrder(): Long = order++

        val events = mutableListOf<Event>()
        for (track in document.tracks) {
            var previous: ResolvedTime? = null
            for (entry in track.entries) {
                val resolved = resolveTrackTime(document, anchors, entry.timeExpr, previous, entry.sourceLine)
                previous = resolved
                val emitCue = entry.emitCue
                val functionCall = entry.functionCall()
                if (emitCue != null) {
                    val cue = cues[emitCue]
                        ?: compileError(document, entry.sourceLine, "KLP4001", "未定义 cue: $emitCue")
                    compileCue(document, addons, cue, resolved, events, ::nextOrder)
                } else if (functionCall != null) {
                    compileFunctionCall(
                        document = document,
                        addons = addons,
                        call = functionCall,
                        sourceLine = entry.sourceLine,
                        base = resolved,
                        defaultCursorId = track.cursorId,
                        defaultZ = track.z,
                        defaultProtect = track.protect,
                        source = "track:${track.name}/func:${functionCall.name}",
                        out = events,
                        nextOrder = ::nextOrder,
                    )
                } else if (entry.ops.isNotEmpty()) {
                    events += Event(
                        timeMs = resolved.timeMs,
                        order = nextOrder(),
                        cursorId = track.cursorId,
                        z = track.z,
                        protect = track.protect,
                        ops = entry.ops,
                        sourceLine = entry.sourceLine,
                        source = "track:${track.name}",
                    )
                }
            }
        }

        return Timeline(
            document,
            events.sortedWith(compareBy<Event> { it.timeMs }.thenBy { it.z }.thenBy { it.order }),
        )
    }

    private fun compileCue(
        document: KlipDocument,
        addons: LuaAddonRegistry,
        cue: Cue,
        emitAt: ResolvedTime,
        out: MutableList<Event>,
        nextOrder: () -> Long,
    ) {
        var previous = ResolvedTime(0L, emitAt.bpm, emitAt.anchorName)
        for (entry in cue.entries) {
            previous = when (entry) {
                is RawEvent -> {
                    val local = resolveCueTime(document, entry.timeExpr, previous, entry.sourceLine)
                    val functionCall = entry.functionCall()
                    if (functionCall != null) {
                        compileFunctionCall(
                            document = document,
                            addons = addons,
                            call = functionCall,
                            sourceLine = entry.sourceLine,
                            base = local.copy(timeMs = emitAt.timeMs + local.timeMs),
                            defaultCursorId = cue.cursorId,
                            defaultZ = cue.z,
                            defaultProtect = cue.protect,
                            source = "cue:${cue.name}/func:${functionCall.name}",
                            out = out,
                            nextOrder = nextOrder,
                        )
                    } else if (entry.ops.isNotEmpty()) {
                        out += Event(
                            timeMs = emitAt.timeMs + local.timeMs,
                            order = nextOrder(),
                            cursorId = cue.cursorId,
                            z = cue.z,
                            protect = cue.protect,
                            ops = entry.ops,
                            sourceLine = entry.sourceLine,
                            source = "cue:${cue.name}",
                        )
                    }
                    local
                }
                is LoopEntry -> compileLoop(document, addons, cue, entry, emitAt, previous, out, nextOrder)
            }
        }
    }

    private fun compileLoop(
        document: KlipDocument,
        addons: LuaAddonRegistry,
        cue: Cue,
        loop: LoopEntry,
        emitAt: ResolvedTime,
        start: ResolvedTime,
        out: MutableList<Event>,
        nextOrder: () -> Long,
    ): ResolvedTime {
        var loopEnd = start
        repeat(loop.count) {
            var previous = loopEnd
            for (entry in loop.entries) {
                val local = resolveCueTime(document, entry.timeExpr, previous, entry.sourceLine)
                val functionCall = entry.functionCall()
                if (functionCall != null) {
                    compileFunctionCall(
                        document = document,
                        addons = addons,
                        call = functionCall,
                        sourceLine = entry.sourceLine,
                        base = local.copy(timeMs = emitAt.timeMs + local.timeMs),
                        defaultCursorId = cue.cursorId,
                        defaultZ = cue.z,
                        defaultProtect = cue.protect,
                        source = "cue:${cue.name}/loop/func:${functionCall.name}",
                        out = out,
                        nextOrder = nextOrder,
                    )
                } else if (entry.ops.isNotEmpty()) {
                    out += Event(
                        timeMs = emitAt.timeMs + local.timeMs,
                        order = nextOrder(),
                        cursorId = cue.cursorId,
                        z = cue.z,
                        protect = cue.protect,
                        ops = entry.ops,
                        sourceLine = entry.sourceLine,
                        source = "cue:${cue.name}/loop",
                    )
                }
                previous = local
            }
            loopEnd = previous
        }
        return loopEnd
    }

    private fun compileFunctionCall(
        document: KlipDocument,
        addons: LuaAddonRegistry,
        call: FunctionCall,
        sourceLine: Int,
        base: ResolvedTime,
        defaultCursorId: String,
        defaultZ: Int,
        defaultProtect: Boolean,
        source: String,
        out: MutableList<Event>,
        nextOrder: () -> Long,
    ) {
        for (generated in addons.expand(document, call, sourceLine, base.bpm)) {
            out += Event(
                timeMs = base.timeMs + generated.offsetMs,
                order = nextOrder(),
                cursorId = generated.cursorId ?: defaultCursorId,
                z = generated.z ?: defaultZ,
                protect = generated.protect ?: defaultProtect,
                ops = generated.ops,
                sourceLine = sourceLine,
                source = source,
            )
        }
    }

    private fun resolveTrackTime(
        document: KlipDocument,
        anchors: Map<String, Anchor>,
        expr: String,
        previous: ResolvedTime?,
        line: Int,
    ): ResolvedTime {
        TimeExpressions.parseAbsolute(expr)?.let {
            return ResolvedTime(it, null, null)
        }
        if (TimeExpressions.looksLikeAbsolute(expr) || TimeExpressions.looksLikeMalformedAbsolute(expr)) {
            compileError(document, line, "KLP5001", "绝对时间无法解析: $expr")
        }
        if (expr.startsWith("+")) {
            val base = previous ?: compileError(document, line, "KLP5001", "相对时间缺少上一事件: $expr")
            val delta = TimeExpressions.parseDuration(expr.drop(1), base.bpm, document, line)
            return base.copy(timeMs = base.timeMs + delta)
        }
        return resolveAnchorTime(document, anchors, expr, line)
    }

    private fun resolveCueTime(
        document: KlipDocument,
        expr: String,
        previous: ResolvedTime,
        line: Int,
    ): ResolvedTime {
        if (!expr.startsWith("+")) {
            compileError(document, line, "KLP2001", "cue 内只允许使用相对时间: $expr")
        }
        val delta = TimeExpressions.parseDuration(expr.drop(1), previous.bpm, document, line)
        return previous.copy(timeMs = previous.timeMs + delta)
    }

    private fun resolveAnchorTime(
        document: KlipDocument,
        anchors: Map<String, Anchor>,
        expr: String,
        line: Int,
    ): ResolvedTime {
        val anchor = anchors.values.sortedByDescending { it.name.length }.firstOrNull {
            expr == it.name || expr.startsWith("${it.name}+") || expr.startsWith("${it.name}-")
        } ?: compileError(document, line, "KLP3001", "未定义 anchor: ${expr.takeWhile { it != '+' && it != '-' }}")

        val rest = expr.removePrefix(anchor.name)
        val offset = when {
            rest.isEmpty() -> 0L
            rest.startsWith("+") -> TimeExpressions.parseDuration(rest.drop(1), anchor.bpm, document, line)
            rest.startsWith("-") -> -TimeExpressions.parseDuration(rest.drop(1), anchor.bpm, document, line)
            else -> compileError(document, line, "KLP5001", "时间表达式无法解析: $expr")
        }
        return ResolvedTime(anchor.timeMs + offset, anchor.bpm, anchor.name)
    }

    private fun anchorsByName(document: KlipDocument): Map<String, Anchor> {
        val map = linkedMapOf<String, Anchor>()
        for (anchor in document.anchors) {
            if (map.containsKey(anchor.name)) {
                compileError(document, anchor.sourceLine, "KLP3002", "重复定义 anchor: ${anchor.name}")
            }
            map[anchor.name] = anchor
        }
        return map
    }

    private fun cuesByName(document: KlipDocument): Map<String, Cue> {
        val map = linkedMapOf<String, Cue>()
        for (cue in document.cues) {
            if (map.containsKey(cue.name)) {
                compileError(document, cue.sourceLine, "KLP4002", "重复定义 cue: ${cue.name}")
            }
            map[cue.name] = cue
        }
        return map
    }

    private fun RawEvent.functionCall(): FunctionCall? =
        ops.singleOrNull() as? FunctionCall

    private fun compileError(document: KlipDocument, line: Int, code: String, detail: String): Nothing =
        throw CompileError(code, document.fileName, line, detail)

    private data class ResolvedTime(
        val timeMs: Long,
        val bpm: Double?,
        val anchorName: String?,
    )
}

object TimeExpressions {
    private val absolute = Regex("(\\d+):(\\d{2})\\.(\\d{3})")
    private val integer = Regex("\\d+")
    private val decimalBeat = Regex("\\d+(?:\\.\\d+)?")
    private val fractionBeat = Regex("(\\d+)/(\\d+)")

    fun parseAbsolute(value: String): Long? {
        val match = absolute.matchEntire(value) ?: return null
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val millis = match.groupValues[3].toLong()
        if (seconds > 59) return null
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    fun looksLikeAbsolute(value: String): Boolean =
        absolute.matches(value)

    fun looksLikeMalformedAbsolute(value: String): Boolean =
        value.firstOrNull()?.isDigit() == true && (':' in value || '.' in value)

    fun parseDuration(raw: String, bpm: Double?, document: KlipDocument, line: Int): Long {
        val value = raw.trim()
        if (value.isEmpty()) compileError(document, line, "KLP5001", "duration 为空")
        if (value.endsWith("ms")) {
            val number = value.dropLast(2)
            if (!integer.matches(number)) compileError(document, line, "KLP5001", "毫秒 duration 无法解析: $raw")
            return number.toLong()
        }
        if (integer.matches(value)) return value.toLong()
        if (value.endsWith("b")) {
            val beatValue = value.dropLast(1)
            val beatCount = parseBeatCount(beatValue, document, line)
            val actualBpm = bpm ?: compileError(document, line, "KLP5001", "相对节拍缺少 BPM 上下文")
            return (60_000.0 / actualBpm * beatCount).roundToLong()
        }
        compileError(document, line, "KLP5001", "duration 无法解析: $raw")
    }

    private fun parseBeatCount(value: String, document: KlipDocument, line: Int): Double {
        fractionBeat.matchEntire(value)?.let {
            val numerator = it.groupValues[1].toDouble()
            val denominator = it.groupValues[2].toDouble()
            if (denominator == 0.0) compileError(document, line, "KLP5001", "分数节拍分母不能为 0")
            return numerator / denominator
        }
        if (decimalBeat.matches(value)) return value.toDouble()
        compileError(document, line, "KLP5001", "节拍 duration 无法解析: $value")
    }

    private fun compileError(document: KlipDocument, line: Int, code: String, detail: String): Nothing =
        throw CompileError(code, document.fileName, line, detail)
}
