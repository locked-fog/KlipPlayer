package kliplayer

import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

interface AudioClock {
    fun start()
    fun currentMs(): Long
    fun stop()
    fun isFinished(): Boolean
}

sealed interface AudioStatus {
    val message: String
    val isFallback: Boolean

    data object NotStarted : AudioStatus {
        override val message: String = "audio: not started"
        override val isFallback: Boolean = false
    }

    data class Playing(val path: Path) : AudioStatus {
        override val message: String = "audio: playing $path"
        override val isFallback: Boolean = false
    }

    data object NoMusicConfigured : AudioStatus {
        override val message: String = "warning: music meta is missing; using monotonic no-audio clock"
        override val isFallback: Boolean = true
    }

    data class MissingFile(val path: Path) : AudioStatus {
        override val message: String = "warning: audio file not found: $path; using monotonic no-audio clock"
        override val isFallback: Boolean = true
    }

    data class Failed(val path: Path, val reason: String) : AudioStatus {
        override val message: String = "warning: audio could not be started for $path ($reason); using monotonic no-audio clock"
        override val isFallback: Boolean = true
    }

    data object Stopped : AudioStatus {
        override val message: String = "audio: stopped"
        override val isFallback: Boolean = false
    }
}

class AudioPlayer private constructor(
    private val musicPath: Path?,
    private val fallbackDurationMs: Long,
) : AudioClock {
    private var clip: Clip? = null
    private var startNanos: Long = 0L
    private var fallbackStartAtMs: Long = 0L
    private var started = false
    private var stoppedAtMs: Long = 0L
    var status: AudioStatus = AudioStatus.NotStarted
        private set

    val modeMessage: String get() = status.message

    override fun start() {
        start(0L)
    }

    fun start(startAtMs: Long) {
        val safeStartAtMs = startAtMs.coerceAtLeast(0L)
        started = true
        stoppedAtMs = 0L
        fallbackStartAtMs = safeStartAtMs
        startNanos = System.nanoTime()
        val path = musicPath
        if (path == null) {
            status = AudioStatus.NoMusicConfigured
            return
        }
        if (!Files.exists(path)) {
            status = AudioStatus.MissingFile(path)
            return
        }
        runCatching {
            AudioSystem.getAudioInputStream(path.toFile()).use { stream ->
                playableAudioInputStream(stream).use { playableStream ->
                    val loaded = AudioSystem.getClip()
                    loaded.open(playableStream)
                    val seekMicros = safeStartAtMs.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
                    val audioLength = loaded.microsecondLength
                    loaded.microsecondPosition = if (audioLength > 0) {
                        seekMicros.coerceAtMost(audioLength)
                    } else {
                        seekMicros
                    }
                    loaded.start()
                    clip = loaded
                    status = AudioStatus.Playing(path)
                }
            }
        }.onFailure {
            clip = null
            startNanos = System.nanoTime()
            status = AudioStatus.Failed(path, it.message ?: it::class.simpleName ?: "unknown error")
        }
    }

    override fun currentMs(): Long {
        val loaded = clip
        if (loaded != null) {
            val length = loaded.microsecondLength
            val position = loaded.microsecondPosition
            if (length > 0 && position >= length) return fallbackDurationMs
            return position / 1_000L
        }
        if (status == AudioStatus.Stopped) return stoppedAtMs
        if (!started) return 0L
        return fallbackStartAtMs + (System.nanoTime() - startNanos) / 1_000_000L
    }

    override fun stop() {
        stoppedAtMs = currentMs()
        clip?.stop()
        clip?.close()
        clip = null
        started = false
        status = AudioStatus.Stopped
    }

    override fun isFinished(): Boolean {
        if (status == AudioStatus.Stopped) return true
        val loaded = clip
        if (loaded != null) {
            return loaded.microsecondLength > 0 && loaded.microsecondPosition >= loaded.microsecondLength
        }
        return currentMs() >= fallbackDurationMs
    }

    companion object {
        private fun playableAudioInputStream(source: AudioInputStream): AudioInputStream {
            val sourceFormat = source.format
            if (sourceFormat.encoding == AudioFormat.Encoding.PCM_SIGNED) return source

            val sampleRate = sourceFormat.sampleRate
            val channels = sourceFormat.channels
            require(sampleRate > 0f && channels > 0) {
                "audio format lacks sample rate or channel count"
            }

            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false,
            )
            return AudioSystem.getAudioInputStream(targetFormat, source)
        }

        fun from(document: KlipDocument, timelineEndMs: Long): AudioPlayer {
            val music = document.meta.music?.let {
                val path = Path.of(it)
                if (path.isAbsolute) path else Path.of(document.fileName).parent?.resolve(path) ?: path
            }
            return AudioPlayer(music, timelineEndMs + 250L)
        }
    }
}
