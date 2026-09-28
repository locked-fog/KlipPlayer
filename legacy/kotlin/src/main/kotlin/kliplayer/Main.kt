package kliplayer

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = Main().run(args)
    if (exitCode != 0) exitProcess(exitCode)
}

interface ShutdownHooks {
    fun add(hook: Thread)
    fun remove(hook: Thread): Boolean
}

private object RuntimeShutdownHooks : ShutdownHooks {
    override fun add(hook: Thread) {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun remove(hook: Thread): Boolean =
        Runtime.getRuntime().removeShutdownHook(hook)
}

class Main(
    private val shutdownHooks: ShutdownHooks = RuntimeShutdownHooks,
) {
    fun run(args: Array<String>): Int {
        val options = parseArgs(args) ?: run {
            usage()
            return 2
        }
        return try {
            val path = Path.of(options.fileName)
            val document = KlipParser.parse(path)
            val timeline = KlipCompiler().compile(document)
            when (options.command) {
                "check" -> check(timeline)
                "compile" -> compile(timeline)
                "play" -> play(timeline, options.startAtMs)
                else -> 2
            }
        } catch (error: KlipException) {
            System.err.println(error.message)
            1
        } catch (error: Exception) {
            System.err.println("KLP9001 runtime: ${error.message}")
            1
        }
    }

    private fun parseArgs(args: Array<String>): CliOptions? {
        if (args.isEmpty()) return null
        return when (args[0]) {
            "check", "compile" -> {
                if (args.size != 2) null else CliOptions(args[0], args[1])
            }
            "play" -> parsePlayArgs(args)
            else -> null
        }
    }

    private fun parsePlayArgs(args: Array<String>): CliOptions? {
        var fileName: String? = null
        var startAtMs = 0L
        var hasStartAt = false
        var index = 1
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--start-at" -> {
                    if (hasStartAt || index + 1 >= args.size) return null
                    startAtMs = parseStartAt(args[index + 1]) ?: return null
                    hasStartAt = true
                    index += 2
                }
                arg.startsWith("--start-at=") -> {
                    if (hasStartAt) return null
                    startAtMs = parseStartAt(arg.substringAfter('=')) ?: return null
                    hasStartAt = true
                    index++
                }
                arg.startsWith("--") -> return null
                fileName == null -> {
                    fileName = arg
                    index++
                }
                else -> return null
            }
        }
        return fileName?.let { CliOptions("play", it, startAtMs) }
    }

    private fun parseStartAt(value: String): Long? =
        TimeExpressions.parseAbsolute(value)

    private fun check(timeline: Timeline): Int {
        val doc = timeline.document
        println("file=${doc.fileName}")
        println("music=${doc.meta.music ?: "<none>"}")
        println("width=${doc.meta.width}")
        println("height=${doc.meta.height}")
        println("anchors=${doc.anchors.size}")
        println("cues=${doc.cues.size}")
        println("tracks=${doc.tracks.size}")
        println("events=${timeline.events.size}")
        println("range=${timeline.startMs}..${timeline.endMs}ms")
        if (doc.meta.music == null) {
            println("warning: music meta is missing; play will use monotonic no-audio mode")
        }
        return 0
    }

    private fun compile(timeline: Timeline): Int {
        for (event in timeline.events) {
            val ops = event.ops.joinToString(" | ") { it.describe() }
            println(
                "${event.timeMs}ms order=${event.order} z=${event.z} cursor=${event.cursorId} " +
                    "protect=${event.protect} line=${event.sourceLine} source=${event.source} :: $ops",
            )
        }
        return 0
    }

    private fun play(timeline: Timeline, startAtMs: Long): Int {
        val renderer = TerminalRenderer(timeline.document.meta.width, timeline.document.meta.height)
        val audio = AudioPlayer.from(timeline.document, timeline.endMs)
        val cleanupDone = AtomicBoolean(false)
        val shutdownHook = Thread(
            {
                cleanupPlayback(audio, renderer, cleanupDone)
            },
            "kliplayer-terminal-restore",
        )
        var index = 0
        var shutdownHookInstalled = false
        try {
            shutdownHooks.add(shutdownHook)
            shutdownHookInstalled = true

            renderer.render(startupClearEvent())
            renderer.flush()

            var rendered = false
            while (index < timeline.events.size && timeline.events[index].timeMs < startAtMs) {
                renderer.render(timeline.events[index])
                rendered = true
                index++
            }
            if (rendered) renderer.flush()
            audio.start(startAtMs)
            if (audio.status.isFallback) {
                System.err.println(audio.status.message)
            }
            while (index < timeline.events.size || !audio.isFinished()) {
                val now = audio.currentMs()
                rendered = false
                while (index < timeline.events.size && timeline.events[index].timeMs <= now) {
                    renderer.render(timeline.events[index])
                    rendered = true
                    index++
                }
                if (rendered) renderer.flush()
                if (index >= timeline.events.size && audio.isFinished()) break
                Thread.sleep(5L)
            }
        } finally {
            if (shutdownHookInstalled) {
                runCatching { shutdownHooks.remove(shutdownHook) }
            }
            cleanupPlayback(audio, renderer, cleanupDone)
        }
        return 0
    }

    private fun startupClearEvent(): Event =
        Event(
            timeMs = 0L,
            order = Long.MIN_VALUE,
            cursorId = "__startup__",
            z = Int.MAX_VALUE,
            protect = false,
            ops = listOf(Clear),
            sourceLine = 0,
            source = "runtime:startup",
        )

    private fun cleanupPlayback(
        audio: AudioClock,
        renderer: TerminalRenderer,
        cleanupDone: AtomicBoolean,
    ) {
        if (!cleanupDone.compareAndSet(false, true)) return
        runCatching { audio.stop() }
        runCatching { renderer.restore() }
    }

    private fun usage() {
        println("usage:")
        println("  kliplayer check <file.klip>")
        println("  kliplayer compile <file.klip>")
        println("  kliplayer play [--start-at MM:SS.mmm] <file.klip>")
    }

    private data class CliOptions(
        val command: String,
        val fileName: String,
        val startAtMs: Long = 0L,
    )
}
