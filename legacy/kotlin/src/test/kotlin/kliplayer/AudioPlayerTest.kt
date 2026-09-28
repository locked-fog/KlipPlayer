package kliplayer

import java.nio.file.Files
import java.util.ServiceLoader
import javax.sound.sampled.spi.AudioFileReader
import javax.sound.sampled.spi.FormatConversionProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioPlayerTest {
    @Test
    fun `bundled java sound providers expose mp3 and flac readers and decoders`() {
        val readers = ServiceLoader.load(AudioFileReader::class.java)
            .map { it::class.java.name }
            .toSet()
        val converters = ServiceLoader.load(FormatConversionProvider::class.java)
            .map { it::class.java.name }
            .toSet()

        assertTrue(
            "javazoom.spi.mpeg.sampled.file.MpegAudioFileReader" in readers,
            "expected bundled MP3 Java Sound reader",
        )
        assertTrue(
            "javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider" in converters,
            "expected bundled MP3 Java Sound decoder",
        )
        assertTrue(
            "org.jflac.sound.spi.FlacAudioFileReader" in readers,
            "expected bundled FLAC Java Sound reader",
        )
        assertTrue(
            "org.jflac.sound.spi.FlacFormatConversionProvider" in converters,
            "expected bundled FLAC Java Sound decoder",
        )
    }

    @Test
    fun `missing music meta uses explicit no-audio status`() {
        val document = KlipDocument(
            fileName = "example.klip",
            meta = Meta(emptyMap()),
            anchors = emptyList(),
            cues = emptyList(),
            tracks = emptyList(),
        )
        val player = AudioPlayer.from(document, timelineEndMs = 0L)

        player.start()

        assertIs<AudioStatus.NoMusicConfigured>(player.status)
        assertTrue(player.status.isFallback)
        assertContains(player.status.message, "music meta is missing")
        player.stop()
    }

    @Test
    fun `fallback clock starts at requested offset`() {
        val document = KlipDocument(
            fileName = "example.klip",
            meta = Meta(emptyMap()),
            anchors = emptyList(),
            cues = emptyList(),
            tracks = emptyList(),
        )
        val player = AudioPlayer.from(document, timelineEndMs = 30_000L)

        player.start(startAtMs = 12_345L)

        assertIs<AudioStatus.NoMusicConfigured>(player.status)
        val currentMs = player.currentMs()
        assertTrue(currentMs >= 12_345L)
        assertTrue(currentMs < 13_000L)
        player.stop()
    }

    @Test
    fun `missing audio file resolves relative to script directory`() {
        val scriptDir = Files.createTempDirectory("kliplayer-audio")
        val document = KlipDocument(
            fileName = scriptDir.resolve("show.klip").toString(),
            meta = Meta(mapOf("music" to "missing.mp3")),
            anchors = emptyList(),
            cues = emptyList(),
            tracks = emptyList(),
        )
        val player = AudioPlayer.from(document, timelineEndMs = 0L)

        player.start()

        val status = assertIs<AudioStatus.MissingFile>(player.status)
        assertTrue(status.isFallback)
        assertTrue(status.path.endsWith("missing.mp3"))
        assertTrue(status.path.startsWith(scriptDir))
        player.stop()
        Files.deleteIfExists(scriptDir)
    }

    @Test
    fun `unsupported audio file reports failed fallback status`() {
        val audioFile = Files.createTempFile("kliplayer-invalid-audio", ".mp3")
        Files.writeString(audioFile, "not an audio file")
        val document = KlipDocument(
            fileName = audioFile.resolveSibling("show.klip").toString(),
            meta = Meta(mapOf("music" to audioFile.fileName.toString())),
            anchors = emptyList(),
            cues = emptyList(),
            tracks = emptyList(),
        )
        val player = AudioPlayer.from(document, timelineEndMs = 0L)

        player.start()

        assertIs<AudioStatus.Failed>(player.status)
        assertTrue(player.status.isFallback)
        assertContains(player.status.message, "audio could not be started")
        player.stop()
        Files.deleteIfExists(audioFile)
    }

    @Test
    fun `stop freezes fallback clock and marks finished`() {
        val document = KlipDocument(
            fileName = "example.klip",
            meta = Meta(emptyMap()),
            anchors = emptyList(),
            cues = emptyList(),
            tracks = emptyList(),
        )
        val player = AudioPlayer.from(document, timelineEndMs = 10_000L)

        player.start()
        Thread.sleep(20L)
        val beforeStop = player.currentMs()
        player.stop()
        val stoppedAt = player.currentMs()
        Thread.sleep(20L)

        assertIs<AudioStatus.Stopped>(player.status)
        assertTrue(beforeStop > 0L)
        assertTrue(stoppedAt >= beforeStop)
        assertEquals(stoppedAt, player.currentMs())
        assertTrue(player.isFinished())
    }
}
