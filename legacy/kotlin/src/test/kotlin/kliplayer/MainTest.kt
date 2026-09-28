package kliplayer

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `play with missing audio prints explicit fallback warning`() {
        val script = Files.createTempFile("kliplayer-missing-audio", ".klip")
        Files.writeString(
            script,
            """
            [meta music="missing.mp3"]
            [meta width=4]
            [meta height=2]
            [track one cursor=main z=1 protect=off]
            [00:00.000][mv 1,1]A
            [endtrack]
            """.trimIndent(),
        )

        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            assertEquals(0, Main().run(arrayOf("play", script.toString())))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            Files.deleteIfExists(script)
        }

        assertTrue(err.toString().contains("warning: audio file not found"))
        assertTrue(err.toString().contains("using monotonic no-audio clock"))
    }

    @Test
    fun `play clears terminal before first script output`() {
        val script = Files.createTempFile("kliplayer-startup-clear", ".klip")
        Files.writeString(
            script,
            """
            [meta width=3]
            [meta height=1]
            [track one cursor=main z=1 protect=off]
            [00:00.000][mv 1,2]A
            [endtrack]
            """.trimIndent(),
        )

        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            assertEquals(0, Main().run(arrayOf("play", "--start-at", "00:01.000", script.toString())))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            Files.deleteIfExists(script)
        }

        val rendered = out.toString()
        val startupClear = rendered.indexOf("\u001b[1;1H   ")
        val firstScriptOutput = rendered.indexOf("\u001b[1;2HA")
        assertTrue(startupClear >= 0)
        assertTrue(firstScriptOutput > startupClear)
    }

    @Test
    fun `play registers and removes terminal restore shutdown hook`() {
        val script = Files.createTempFile("kliplayer-shutdown-hook", ".klip")
        Files.writeString(
            script,
            """
            [meta width=1]
            [meta height=1]
            [track one cursor=main z=1 protect=off]
            [00:00.000]A
            [endtrack]
            """.trimIndent(),
        )

        val hooks = RecordingShutdownHooks()
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            assertEquals(0, Main(hooks).run(arrayOf("play", "--start-at", "00:01.000", script.toString())))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            Files.deleteIfExists(script)
        }

        assertEquals(1, hooks.added.size)
        assertEquals("kliplayer-terminal-restore", hooks.added.single().name)
        assertEquals(1, hooks.removed.size)
        assertTrue(hooks.active.isEmpty())
    }

    @Test
    fun `play start-at renders earlier commands without waiting for their timestamps`() {
        val script = Files.createTempFile("kliplayer-start-at", ".klip")
        Files.writeString(
            script,
            """
            [meta width=4]
            [meta height=2]
            [track one cursor=main z=1 protect=off]
            [00:10.000][mv 1,1]A
            [endtrack]
            """.trimIndent(),
        )

        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            assertEquals(
                0,
                Main().run(arrayOf("play", "--start-at", "00:20.000", script.toString())),
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            Files.deleteIfExists(script)
        }

        assertContains(out.toString(), "A")
        assertContains(err.toString(), "using monotonic no-audio clock")
    }

    @Test
    fun `play start-at requires absolute time`() {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(out))
            System.setErr(PrintStream(err))
            assertEquals(2, Main().run(arrayOf("play", "--start-at", "bad", "example.klip")))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        assertContains(out.toString(), "kliplayer play [--start-at MM:SS.mmm] <file.klip>")
        assertEquals("", err.toString())
    }

    private class RecordingShutdownHooks : ShutdownHooks {
        val added = mutableListOf<Thread>()
        val removed = mutableListOf<Thread>()
        val active = mutableListOf<Thread>()

        override fun add(hook: Thread) {
            added += hook
            active += hook
        }

        override fun remove(hook: Thread): Boolean {
            removed += hook
            return active.remove(hook)
        }
    }
}
