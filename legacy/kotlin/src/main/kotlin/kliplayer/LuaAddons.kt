package kliplayer

import java.nio.file.Files
import java.nio.file.Path
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction

internal class LuaAddonRegistry private constructor(
    private val functions: Map<String, RegisteredFunction>,
) {
    fun expand(
        document: KlipDocument,
        call: FunctionCall,
        sourceLine: Int,
        bpm: Double?,
    ): List<LuaGeneratedEvent> {
        val registered = functions[call.name]
            ?: compileError(document, sourceLine, "KLP6003", "未定义 function: ${call.name}")
        val ctx = LuaInvocationContext(document, call, sourceLine, bpm).toLuaTable()
        val result = try {
            registered.function.call(ctx)
        } catch (error: LuaError) {
            compileError(document, sourceLine, "KLP6004", "Lua function ${call.name} 执行失败: ${error.message}")
        } catch (error: Exception) {
            compileError(document, sourceLine, "KLP6004", "Lua function ${call.name} 执行失败: ${error.message}")
        }

        return try {
            LuaReturnParser(call.name).parseEvents(result)
        } catch (error: LuaAddonFailure) {
            compileError(document, sourceLine, "KLP6004", error.message ?: "Lua function ${call.name} 返回结构非法")
        }
    }

    companion object {
        fun load(document: KlipDocument): LuaAddonRegistry {
            val loaded = linkedMapOf<String, RegisteredFunction>()
            for ((index, addon) in document.meta.addons.withIndex()) {
                val sourceLine = document.meta.addonSourceLines.getOrElse(index) { 0 }
                val path = resolveAddonPath(document, addon)
                val addonTable = loadAddonTable(document, sourceLine, path)
                val functions = addonTable.get("functions")
                if (!functions.istable()) {
                    compileError(document, sourceLine, "KLP6001", "addon 缺少 functions 表: $addon")
                }
                registerFunctions(document, sourceLine, addon, functions.checktable(), loaded)
            }
            return LuaAddonRegistry(loaded)
        }

        private fun resolveAddonPath(document: KlipDocument, addon: String): Path {
            val raw = Path.of(addon)
            if (raw.isAbsolute) return raw.normalize()
            val documentPath = Path.of(document.fileName)
            val baseDir = documentPath.parent ?: Path.of("")
            return baseDir.resolve(raw).normalize()
        }

        private fun loadAddonTable(
            document: KlipDocument,
            sourceLine: Int,
            path: Path,
        ): LuaTable {
            val globals = restrictedGlobals()
            val script = try {
                Files.readString(path)
            } catch (error: Exception) {
                compileError(document, sourceLine, "KLP6001", "addon 加载失败: $path (${error.message})")
            }
            val result = try {
                globals.load(script, "@$path").call()
            } catch (error: Exception) {
                compileError(document, sourceLine, "KLP6001", "addon 加载失败: $path (${error.message})")
            }
            if (!result.istable()) {
                compileError(document, sourceLine, "KLP6001", "addon 必须 return table: $path")
            }
            return result.checktable()
        }

        private fun restrictedGlobals(): Globals {
            val globals = Globals()
            globals.load(BaseLib())
            val packageTable = LuaTable()
            packageTable.set("loaded", LuaTable())
            globals.set("package", packageTable)
            globals.load(TableLib())
            globals.load(StringLib())
            globals.load(MathLib())
            LoadState.install(globals)
            LuaC.install(globals)
            for (name in BLOCKED_GLOBALS) {
                globals.set(name, LuaValue.NIL)
            }
            return globals
        }

        private fun registerFunctions(
            document: KlipDocument,
            sourceLine: Int,
            addon: String,
            functionsTable: LuaTable,
            out: MutableMap<String, RegisteredFunction>,
        ) {
            var key: LuaValue = LuaValue.NIL
            while (true) {
                val pair = functionsTable.next(key)
                key = pair.arg1()
                if (key.isnil()) break
                val value = pair.arg(2)
                if (!key.isstring()) {
                    compileError(document, sourceLine, "KLP6001", "addon function 名称必须是字符串: $addon")
                }
                val name = key.checkjstring()
                if (!IDENTIFIER.matches(name)) {
                    compileError(document, sourceLine, "KLP6001", "非法 addon function 名称: $name")
                }
                if (!value.isfunction()) {
                    compileError(document, sourceLine, "KLP6001", "addon function 必须是函数: $name")
                }
                if (out.containsKey(name)) {
                    compileError(document, sourceLine, "KLP6002", "重复注册 function: $name")
                }
                out[name] = RegisteredFunction(value)
            }
        }

        private val BLOCKED_GLOBALS = listOf(
            "io",
            "os",
            "package",
            "debug",
            "luajava",
            "require",
            "loadfile",
            "dofile",
            "load",
            "loadstring",
        )

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    }
}

internal data class LuaGeneratedEvent(
    val offsetMs: Long,
    val cursorId: String?,
    val z: Int?,
    val protect: Boolean?,
    val ops: List<Op>,
)

private data class RegisteredFunction(val function: LuaValue)

private class LuaInvocationContext(
    private val document: KlipDocument,
    private val call: FunctionCall,
    private val sourceLine: Int,
    private val bpm: Double?,
) {
    fun toLuaTable(): LuaTable {
        val ctx = LuaTable()
        ctx.set("string", stringFunction())
        ctx.set("int", intFunction())
        ctx.set("bool", boolFunction())
        ctx.set("color", colorFunction())
        ctx.set("duration", durationFunction())
        ctx.set("chars", charsFunction())
        return ctx
    }

    private fun stringFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                return LuaValue.valueOf(argument(name, defaultString(args)))
            }
        }

    private fun intFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                val raw = argument(name, defaultString(args))
                val value = raw.toIntOrNull() ?: throw LuaError("参数必须是整数: $name")
                return LuaValue.valueOf(value)
            }
        }

    private fun boolFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                val raw = argument(name, defaultString(args))
                return when (raw) {
                    "true", "on" -> LuaValue.TRUE
                    "false", "off" -> LuaValue.FALSE
                    else -> throw LuaError("参数必须是布尔值: $name")
                }
            }
        }

    private fun colorFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                val raw = argument(name, defaultString(args))
                return LuaValue.valueOf(parseColor(raw, "参数颜色非法: $name") ?: "default")
            }
        }

    private fun durationFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val name = args.checkjstring(1)
                val raw = argument(name, defaultString(args))
                val duration = try {
                    TimeExpressions.parseDuration(raw, bpm, document, sourceLine)
                } catch (error: CompileError) {
                    throw LuaError(error.message ?: "duration 无法解析: $raw")
                }
                return LuaValue.valueOf(duration.toDouble())
            }
        }

    private fun charsFunction(): VarArgFunction =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.checkjstring(1)
                val table = LuaTable()
                var index = 1
                val iterator = text.codePoints().iterator()
                while (iterator.hasNext()) {
                    table.set(index++, LuaValue.valueOf(String(Character.toChars(iterator.nextInt()))))
                }
                return table
            }
        }

    private fun argument(name: String, defaultValue: String?): String =
        call.args[name] ?: defaultValue ?: throw LuaError("缺少参数: $name")

    private fun defaultString(args: Varargs): String? =
        if (args.narg() >= 2 && !args.isnil(2)) args.tojstring(2) else null
}

private class LuaReturnParser(
    private val functionName: String,
) {
    fun parseEvents(value: LuaValue): List<LuaGeneratedEvent> {
        if (!value.istable()) fail("Lua function $functionName 必须返回事件数组")
        val table = value.checktable()
        val events = mutableListOf<LuaGeneratedEvent>()
        var index = 1
        while (true) {
            val item = table.get(index)
            if (item.isnil()) break
            events += parseEvent(item, index)
            index++
        }
        if (events.isEmpty() && !table.get("ops").isnil()) {
            fail("Lua function $functionName 必须返回事件数组，而不是单个事件")
        }
        return events
    }

    private fun parseEvent(value: LuaValue, index: Int): LuaGeneratedEvent {
        if (!value.istable()) fail("event #$index 必须是 table")
        val table = value.checktable()
        val offset = optionalLong(table.get("offset"), 0L, "event #$index offset")
        if (offset < 0) fail("event #$index offset 不能为负数")
        val opsValue = table.get("ops")
        if (!opsValue.istable()) fail("event #$index ops 必须是 table")
        return LuaGeneratedEvent(
            offsetMs = offset,
            cursorId = optionalCursor(table.get("cursor"), "event #$index cursor"),
            z = optionalZ(table.get("z"), "event #$index z"),
            protect = optionalBoolean(table.get("protect"), "event #$index protect"),
            ops = parseOps(opsValue.checktable(), index),
        )
    }

    private fun parseOps(table: LuaTable, eventIndex: Int): List<Op> {
        val ops = mutableListOf<Op>()
        var index = 1
        while (true) {
            val item = table.get(index)
            if (item.isnil()) break
            ops += parseOp(item, eventIndex, index)
            index++
        }
        if (ops.isEmpty()) fail("event #$eventIndex ops 不能为空")
        return ops
    }

    private fun parseOp(value: LuaValue, eventIndex: Int, opIndex: Int): Op {
        if (!value.istable()) fail("event #$eventIndex op #$opIndex 必须是 table")
        val table = value.checktable()
        val op = requiredString(table.get("op"), "event #$eventIndex op #$opIndex op")
        return when (op) {
            "mv" -> Move(
                row = positiveInt(table.get("row"), "event #$eventIndex op #$opIndex row"),
                col = positiveInt(table.get("col"), "event #$eventIndex op #$opIndex col"),
            )
            "text" -> Text(requiredString(table.get("value"), "event #$eventIndex op #$opIndex value"))
            "color" -> Foreground(parseColor(requiredString(table.get("value"), "event #$eventIndex op #$opIndex value"), "颜色非法"))
            "background" -> Background(parseColor(requiredString(table.get("value"), "event #$eventIndex op #$opIndex value"), "背景色非法"))
            "style" -> parseStyle(table, eventIndex, opIndex)
            "space" -> Space(nonNegativeInt(table.get("count"), "event #$eventIndex op #$opIndex count"))
            "newline" -> Newline
            "cleanline" -> CleanLine
            "clear" -> Clear
            "hide" -> HideCursor
            "show" -> ShowCursor
            else -> fail("未知 Lua op: $op")
        }
    }

    private fun parseStyle(table: LuaTable, eventIndex: Int, opIndex: Int): Style {
        val name = requiredString(table.get("name"), "event #$eventIndex op #$opIndex name")
        if (name == "default") return Style(null, null)
        if (name !in STYLES) fail("未知 style: $name")
        val enabled = requiredBoolean(table.get("enabled"), "event #$eventIndex op #$opIndex enabled")
        return Style(name, enabled)
    }

    private fun optionalCursor(value: LuaValue, label: String): String? {
        if (value.isnil()) return null
        val cursor = requiredString(value, label)
        if (!IDENTIFIER.matches(cursor)) fail("$label 非法: $cursor")
        return cursor
    }

    private fun optionalZ(value: LuaValue, label: String): Int? {
        if (value.isnil()) return null
        val z = requiredInt(value, label)
        if (z < 0) fail("$label 必须是非负整数")
        return z
    }

    private fun optionalBoolean(value: LuaValue, label: String): Boolean? =
        if (value.isnil()) null else requiredBoolean(value, label)

    private fun optionalLong(value: LuaValue, defaultValue: Long, label: String): Long =
        if (value.isnil()) defaultValue else requiredLong(value, label)

    private fun positiveInt(value: LuaValue, label: String): Int {
        val parsed = requiredInt(value, label)
        if (parsed <= 0) fail("$label 必须从 1 开始")
        return parsed
    }

    private fun nonNegativeInt(value: LuaValue, label: String): Int {
        val parsed = requiredInt(value, label)
        if (parsed < 0) fail("$label 不能为负数")
        return parsed
    }

    private fun requiredString(value: LuaValue, label: String): String {
        if (value.isnil()) fail("$label 缺失")
        if (!value.isstring()) fail("$label 必须是字符串")
        return value.checkjstring()
    }

    private fun requiredInt(value: LuaValue, label: String): Int {
        if (value.isnil()) fail("$label 缺失")
        if (!value.isnumber()) fail("$label 必须是整数")
        val intValue = value.checkint()
        if (value.todouble() != intValue.toDouble()) fail("$label 必须是整数")
        return intValue
    }

    private fun requiredLong(value: LuaValue, label: String): Long {
        if (value.isnil()) fail("$label 缺失")
        if (!value.isnumber()) fail("$label 必须是整数")
        val longValue = value.checklong()
        if (value.todouble() != longValue.toDouble()) fail("$label 必须是整数")
        return longValue
    }

    private fun requiredBoolean(value: LuaValue, label: String): Boolean {
        if (value.isboolean()) return value.toboolean()
        if (value.isstring()) {
            return when (value.checkjstring()) {
                "true", "on" -> true
                "false", "off" -> false
                else -> fail("$label 必须是布尔值")
            }
        }
        fail("$label 必须是布尔值")
    }

    private fun fail(message: String): Nothing =
        throw LuaAddonFailure(message)

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        private val STYLES = setOf("bold", "italic", "underline", "strikeline")
    }
}

private fun parseColor(value: String, label: String): String? {
    if (value == "default") return null
    if (!COLOR.matches(value)) throw LuaAddonFailure("$label: $value")
    return value.lowercase()
}

private fun compileError(document: KlipDocument, line: Int, code: String, detail: String): Nothing =
    throw CompileError(code, document.fileName, line, detail)

private class LuaAddonFailure(message: String) : RuntimeException(message)

private val COLOR = Regex("[0-9A-Fa-f]{6}")
