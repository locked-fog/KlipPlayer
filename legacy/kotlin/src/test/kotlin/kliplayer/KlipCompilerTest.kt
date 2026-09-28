package kliplayer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KlipCompilerTest {
    @Test
    fun `absolute anchor relative and fraction beat times compile`() {
        val timeline = compile(
            """
            [anchor intro 00:01.000 bpm=120]
            [track lyrics cursor=main z=100 protect=on]
            [intro+1/2b][mv 1,1]A
            [+1b][mv 1,2]B
            [00:05.000][mv 1,3]C
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(1250L, 1750L, 5000L), timeline.events.map { it.timeMs })
    }

    @Test
    fun `explicit ms relative duration compiles`() {
        val timeline = compile(
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:01.000]A
            [+500ms]B
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(1000L, 1500L), timeline.events.map { it.timeMs })
    }

    @Test
    fun `track source relative time and final sorting are separate`() {
        val timeline = compile(
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:10.000]A
            [00:05.000]B
            [+500]C
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(5000L, 5500L, 10000L), timeline.events.map { it.timeMs })
        assertEquals(listOf("B", "C", "A"), timeline.events.map { (it.ops.single() as Text).value })
    }

    @Test
    fun `cue emit and loop are expanded at compile time`() {
        val timeline = compile(
            """
            [anchor intro 00:01.000 bpm=120]
            [cue blink cursor=fx z=20 protect=off]
            [loop 2]
            [+100]A
            [+200]B
            [endloop]
            [endcue]

            [track fx cursor=fx z=20 protect=off]
            [intro+1/2b][emit blink]
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(1350L, 1550L, 1650L, 1850L), timeline.events.map { it.timeMs })
        assertEquals(listOf("A", "B", "A", "B"), timeline.events.map { (it.ops.single() as Text).value })
        assertTrue(timeline.events.all { it.source.startsWith("cue:blink") })
    }

    @Test
    fun `relative beat after absolute time fails without bpm context`() {
        val error = assertCompileFails(
            "KLP5001",
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:01.000]A
            [+1b]B
            [endtrack]
            """.trimIndent(),
            "相对节拍缺少 BPM 上下文",
        )
        assertEquals(3, error.sourceLine)
    }

    @Test
    fun `reports duplicate anchors and cues`() {
        assertCompileFails(
            "KLP3002",
            """
            [anchor intro 00:00.000 bpm=120]
            [anchor intro 00:01.000 bpm=120]
            """.trimIndent(),
            "重复定义 anchor",
        )
        assertCompileFails(
            "KLP4002",
            """
            [cue flash cursor=fx z=1 protect=off]
            [+0]A
            [endcue]
            [cue flash cursor=fx z=1 protect=off]
            [+0]B
            [endcue]
            """.trimIndent(),
            "重复定义 cue",
        )
    }

    @Test
    fun `reports undefined anchor and cue`() {
        assertCompileFails(
            "KLP3001",
            """
            [track lyrics cursor=main z=100 protect=on]
            [missing+1b]A
            [endtrack]
            """.trimIndent(),
            "未定义 anchor",
        )
        assertCompileFails(
            "KLP4001",
            """
            [track fx cursor=fx z=1 protect=off]
            [00:00.000][emit nope]
            [endtrack]
            """.trimIndent(),
            "未定义 cue",
        )
    }

    @Test
    fun `reports illegal cue time and malformed durations`() {
        assertCompileFails(
            "KLP2001",
            """
            [cue bad cursor=fx z=1 protect=off]
            [00:01.000]A
            [endcue]
            [track fx cursor=fx z=1 protect=off]
            [00:00.000][emit bad]
            [endtrack]
            """.trimIndent(),
            "cue 内只允许使用相对时间",
        )
        assertCompileFails(
            "KLP5001",
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:60.000]A
            [endtrack]
            """.trimIndent(),
            "绝对时间无法解析",
        )
        assertCompileFails(
            "KLP5001",
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:1.000]A
            [endtrack]
            """.trimIndent(),
            "绝对时间无法解析",
        )
        assertCompileFails(
            "KLP5001",
            """
            [track lyrics cursor=main z=100 protect=on]
            [00:00.00]A
            [endtrack]
            """.trimIndent(),
            "绝对时间无法解析",
        )
        assertCompileFails(
            "KLP5001",
            """
            [anchor intro 00:00.000 bpm=120]
            [track lyrics cursor=main z=100 protect=on]
            [intro++2b]A
            [endtrack]
            """.trimIndent(),
            "节拍 duration 无法解析",
        )
        assertCompileFails(
            "KLP5001",
            """
            [anchor intro 00:00.000 bpm=120]
            [track lyrics cursor=main z=100 protect=on]
            [intro+1/0b]A
            [endtrack]
            """.trimIndent(),
            "分数节拍分母不能为 0",
        )
    }

    @Test
    fun `demo file compiles`() {
        val path = Path.of("examples/demo.klip")
        val doc = KlipParser.parse(path)
        val timeline = KlipCompiler().compile(doc)

        assertEquals(2, doc.anchors.size)
        assertEquals(2, doc.cues.size)
        assertEquals(2, doc.tracks.size)
        assertTrue(timeline.events.isNotEmpty())
        assertTrue(Files.exists(path))
    }

    @Test
    fun `lua function expands track events without changing relative time basis`() {
        val timeline = compileTemp(
            addons = mapOf("addons/type.lua" to TYPE_ADDON),
            script = """
            [meta addon="addons/type.lua"]
            [track lyrics cursor=main z=10 protect=on]
            [00:01.000][func type text="A字" interval=80ms]
            [+100]tail
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(1000L, 1080L, 1100L), timeline.events.map { it.timeMs })
        assertEquals(listOf("A", "字", "tail"), timeline.events.map { (it.ops.single() as Text).value })
        assertEquals(listOf("main", "main"), timeline.events.take(2).map { it.cursorId })
        assertEquals(listOf(10, 10), timeline.events.take(2).map { it.z })
        assertEquals(listOf(true, true), timeline.events.take(2).map { it.protect })
        assertTrue(timeline.events.take(2).all { it.source == "track:lyrics/func:type" })
    }

    @Test
    fun `lua function expands cue events and can override defaults`() {
        val timeline = compileTemp(
            addons = mapOf("addons/fx.lua" to FX_ADDON),
            script = """
            [meta addon="addons/fx.lua"]
            [anchor intro 00:02.000 bpm=120]
            [cue flash cursor=cue z=5 protect=on]
            [+0][func paint]
            [endcue]
            [track fx cursor=track z=1 protect=off]
            [intro][emit flash]
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf(2000L, 2010L), timeline.events.map { it.timeMs })
        assertEquals("cue", timeline.events[0].cursorId)
        assertEquals(5, timeline.events[0].z)
        assertEquals(true, timeline.events[0].protect)
        assertEquals("alt", timeline.events[1].cursorId)
        assertEquals(9, timeline.events[1].z)
        assertEquals(false, timeline.events[1].protect)
        assertIs<Move>(timeline.events[1].ops[0])
        assertIs<Foreground>(timeline.events[1].ops[1])
        assertIs<Style>(timeline.events[1].ops[2])
        assertIs<Background>(timeline.events[1].ops[3])
        assertIs<Space>(timeline.events[1].ops[4])
        assertEquals(Newline, timeline.events[1].ops[5])
        assertEquals(CleanLine, timeline.events[1].ops[6])
        assertEquals(Clear, timeline.events[1].ops[7])
        assertEquals(HideCursor, timeline.events[1].ops[8])
        assertEquals(ShowCursor, timeline.events[1].ops[9])
    }

    @Test
    fun `reports lua addon and function errors`() {
        assertCompileFails(
            "KLP6003",
            """
            [track lyrics cursor=main z=1 protect=off]
            [00:00.000][func missing]
            [endtrack]
            """.trimIndent(),
            "未定义 function",
        )
        assertCompileTempFails(
            "KLP6001",
            addons = emptyMap(),
            script = """
            [meta addon="addons/missing.lua"]
            [track lyrics cursor=main z=1 protect=off]
            [00:00.000]x
            [endtrack]
            """.trimIndent(),
            expectedMessage = "addon 加载失败",
        )
        assertCompileTempFails(
            "KLP6002",
            addons = mapOf(
                "addons/a.lua" to "return { functions = { same = function(ctx) return {} end } }",
                "addons/b.lua" to "return { functions = { same = function(ctx) return {} end } }",
            ),
            script = """
            [meta addon="addons/a.lua"]
            [meta addon="addons/b.lua"]
            [track lyrics cursor=main z=1 protect=off]
            [00:00.000]x
            [endtrack]
            """.trimIndent(),
            expectedMessage = "重复注册 function",
        )
    }

    @Test
    fun `reports lua execution and return schema errors`() {
        assertCompileTempFails(
            "KLP6004",
            addons = mapOf("addons/bad.lua" to "return { functions = { bad = function(ctx) error('boom') end } }"),
            script = luaCallScript("bad"),
            expectedMessage = "执行失败",
        )
        assertCompileTempFails(
            "KLP6004",
            addons = mapOf("addons/bad.lua" to "return { functions = { bad = function(ctx) return 1 end } }"),
            script = luaCallScript("bad"),
            expectedMessage = "必须返回事件数组",
        )
        assertCompileTempFails(
            "KLP6004",
            addons = mapOf(
                "addons/bad.lua" to
                    "return { functions = { bad = function(ctx) return {{ offset = 0, ops = {{ op = 'nope' }} }} end } }",
            ),
            script = luaCallScript("bad"),
            expectedMessage = "未知 Lua op",
        )
    }

    @Test
    fun `lua globals do not expose filesystem os package require or luajava`() {
        val timeline = compileTemp(
            addons = mapOf(
                "addons/sandbox.lua" to """
                return {
                  functions = {
                    sandbox = function(ctx)
                      if os ~= nil or io ~= nil or package ~= nil or require ~= nil or luajava ~= nil then
                        error("unsafe global is available")
                      end
                      return {
                        { offset = 0, ops = { { op = "text", value = "ok" } } }
                      }
                    end
                  }
                }
                """.trimIndent(),
            ),
            script = """
            [meta addon="addons/sandbox.lua"]
            [track lyrics cursor=main z=1 protect=off]
            [00:00.000][func sandbox]
            [endtrack]
            """.trimIndent(),
        )

        assertEquals("ok", (timeline.events.single().ops.single() as Text).value)
    }

    private fun compile(text: String): Timeline =
        KlipCompiler().compile(KlipParser.parseText(text))

    private fun assertCompileFails(code: String, script: String, expectedMessage: String): CompileError {
        val error = assertFailsWith<CompileError> {
            compile(script)
        }
        assertEquals(code, error.code)
        assertContains(error.message ?: "", expectedMessage)
        return error
    }

    private fun compileTemp(addons: Map<String, String>, script: String): Timeline {
        val dir = Files.createTempDirectory("klip-lua-test")
        for ((relativePath, content) in addons) {
            val addonPath = dir.resolve(relativePath)
            Files.createDirectories(addonPath.parent)
            Files.writeString(addonPath, content.trimIndent())
        }
        val scriptPath = dir.resolve("song.klip")
        Files.writeString(scriptPath, script.trimIndent())
        return KlipCompiler().compile(KlipParser.parse(scriptPath))
    }

    private fun assertCompileTempFails(
        code: String,
        addons: Map<String, String>,
        script: String,
        expectedMessage: String,
    ): CompileError {
        val error = assertFailsWith<CompileError> {
            compileTemp(addons, script)
        }
        assertEquals(code, error.code)
        assertContains(error.message ?: "", expectedMessage)
        return error
    }

    private fun luaCallScript(functionName: String): String =
        """
        [meta addon="addons/bad.lua"]
        [track lyrics cursor=main z=1 protect=off]
        [00:00.000][func $functionName]
        [endtrack]
        """.trimIndent()

    private companion object {
        val TYPE_ADDON = """
            return {
              functions = {
                type = function(ctx)
                  local text = ctx.string("text")
                  local interval = ctx.duration("interval", "80ms")
                  local events = {}
                  for i, ch in ipairs(ctx.chars(text)) do
                    events[#events + 1] = {
                      offset = (i - 1) * interval,
                      ops = {
                        { op = "text", value = ch }
                      }
                    }
                  end
                  return events
                end
              }
            }
        """.trimIndent()

        val FX_ADDON = """
            return {
              functions = {
                paint = function(ctx)
                  return {
                    {
                      offset = 0,
                      ops = {
                        { op = "text", value = "base" }
                      }
                    },
                    {
                      offset = 10,
                      cursor = "alt",
                      z = 9,
                      protect = false,
                      ops = {
                        { op = "mv", row = 2, col = 3 },
                        { op = "color", value = "FF0000" },
                        { op = "style", name = "bold", enabled = true },
                        { op = "background", value = "default" },
                        { op = "space", count = 1 },
                        { op = "newline" },
                        { op = "cleanline" },
                        { op = "clear" },
                        { op = "hide" },
                        { op = "show" }
                      }
                    }
                  }
                end
              }
            }
        """.trimIndent()
    }
}
