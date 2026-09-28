package kliplayer

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KlipParserTest {
    @Test
    fun `parses meta anchor cue and track`() {
        val doc = KlipParser.parseText(
            """
            [meta music="song.mp3"]
            [meta width=80]
            [meta height=24]
            [anchor intro 00:01.000 bpm=120]

            [cue flash cursor=warn z=80 protect=off]
            [+0][mv 1,2]GO
            [endcue]

            [track lyrics cursor=main z=100 protect=on]
            [intro+1b][mv 3,4]熱異常
            [endtrack]
            """.trimIndent(),
        )

        assertEquals("song.mp3", doc.meta.music)
        assertEquals(80, doc.meta.width)
        assertEquals(24, doc.meta.height)
        assertEquals(1, doc.anchors.size)
        assertEquals(1, doc.cues.size)
        assertEquals(1, doc.tracks.size)
        assertIs<Move>(doc.tracks.single().entries.single().ops.first())
    }

    @Test
    fun `preserves multiple addon metadata and parses function call`() {
        val doc = KlipParser.parseText(
            """
            [meta addon="addons/type.lua"]
            [meta addon="addons/flash.lua"]
            [track lyrics cursor=main z=100 protect=on]
            [00:00.000][func type text="abc" interval=80ms]
            [endtrack]
            """.trimIndent(),
        )

        assertEquals(listOf("addons/type.lua", "addons/flash.lua"), doc.meta.addons)
        val call = assertIs<FunctionCall>(doc.tracks.single().entries.single().ops.single())
        assertEquals("type", call.name)
        assertEquals(mapOf("text" to "abc", "interval" to "80ms"), call.args)
    }

    @Test
    fun `keeps url text containing slash slash`() {
        val doc = KlipParser.parseText(
            """
            [track links cursor=main z=1 protect=off]
            [00:01.000]https://github.com/locked-fog/CLIPlayer
            [endtrack]
            """.trimIndent(),
        )

        val text = doc.tracks.single().entries.single().ops.single() as Text
        assertEquals("https://github.com/locked-fog/CLIPlayer", text.value)
    }

    @Test
    fun `comments whitespace and escapes are parsed by spec rules`() {
        val doc = KlipParser.parseText(
            """
            // full line comment
              // indented full line comment
            [track text cursor=main z=1 protect=off]
              [00:00.000]   [mv 1,1]   escaped \[tag\] \\ \n done
            [endtrack]
            """.trimIndent(),
        )

        val ops = doc.tracks.single().entries.single().ops
        assertIs<Move>(ops[0])
        assertEquals("   escaped [tag] \\ \n done", (ops[1] as Text).value)
    }

    @Test
    fun `parses background style newline hide show clean commands`() {
        val doc = KlipParser.parseText(
            """
            [track commands cursor=main z=1 protect=off]
            [00:00.000][background 101010][style bold on][newline][hide][show][cleanline][clear]
            [endtrack]
            """.trimIndent(),
        )

        val ops = doc.tracks.single().entries.single().ops
        assertIs<Background>(ops[0])
        assertIs<Style>(ops[1])
        assertEquals(Newline, ops[2])
        assertEquals(HideCursor, ops[3])
        assertEquals(ShowCursor, ops[4])
        assertEquals(CleanLine, ops[5])
        assertEquals(Clear, ops[6])
    }

    @Test
    fun `cue rejects wrong endtrack terminator during parse`() {
        assertParseFails(
            """
            [cue bad cursor=fx z=1 protect=off]
            [endtrack]
            """.trimIndent(),
            "[endtrack] 出现在 cue 内",
        )
    }

    @Test
    fun `rejects malformed top level and block declarations`() {
        assertParseFails("[foo]", "未知顶层标签")
        assertParseFails("[meta width=wide]", "meta width 必须是正整数")
        assertParseFails("[meta title=\"bad]", "字符串缺少右引号")
        assertParseFails(
            """
            [track bad cursor=main z=-1 protect=off]
            [endtrack]
            """.trimIndent(),
            "z 必须是非负整数",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=maybe]
            [endtrack]
            """.trimIndent(),
            "protect 必须是 on 或 off",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 unknown=x]
            [endtrack]
            """.trimIndent(),
            "未知参数",
        )
        assertParseFails(
            """
            [track bad cursor=main cursor=other z=1]
            [endtrack]
            """.trimIndent(),
            "重复参数",
        )
    }

    @Test
    fun `rejects malformed commands`() {
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][mv 0,1]x
            [endtrack]
            """.trimIndent(),
            "mv row 和 col 必须从 1 开始",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][style blink on]x
            [endtrack]
            """.trimIndent(),
            "未知 style",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][newline now]x
            [endtrack]
            """.trimIndent(),
            "newline 不接受参数",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][space 1 2]x
            [endtrack]
            """.trimIndent(),
            "space 语法",
        )
    }

    @Test
    fun `rejects illegal emit and loop placement`() {
        assertParseFails(
            """
            [cue bad cursor=fx z=1 protect=off]
            [+0][emit other]
            [endcue]
            """.trimIndent(),
            "cue 内不允许使用 emit",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][emit cue]text
            [endtrack]
            """.trimIndent(),
            "emit 行不能混写",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [loop 2]
            [endtrack]
            """.trimIndent(),
            "loop 只允许出现在 cue 内",
        )
    }

    @Test
    fun `rejects malformed function calls`() {
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][func 1bad text=x]
            [endtrack]
            """.trimIndent(),
            "非法标识符",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][func type text=a text=b]
            [endtrack]
            """.trimIndent(),
            "重复参数",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][func type text=a]x
            [endtrack]
            """.trimIndent(),
            "func 行不能混写",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][func type text=a][mv 1,1]
            [endtrack]
            """.trimIndent(),
            "func 行不能混写",
        )
        assertParseFails(
            """
            [track bad cursor=main z=1 protect=off]
            [00:00.000][func type text=a][emit flash]
            [endtrack]
            """.trimIndent(),
            "func 行不能混写",
        )
    }

    private fun assertParseFails(script: String, expectedMessage: String): ParseError {
        val error = assertFailsWith<ParseError> {
            KlipParser.parseText(script)
        }
        assertEquals("KLP1001", error.code)
        assertContains(error.message ?: "", expectedMessage)
        return error
    }
}
