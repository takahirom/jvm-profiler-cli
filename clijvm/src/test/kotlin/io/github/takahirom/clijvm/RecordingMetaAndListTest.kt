package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.cli.MAX_DISPLAY_NAME
import io.github.takahirom.clijvm.cli.drillGuidance
import io.github.takahirom.clijvm.cli.recordingMainClassCell
import io.github.takahirom.clijvm.cli.truncateDisplayName
import io.github.takahirom.clijvm.util.RecordingMeta
import io.github.takahirom.clijvm.util.readRecordingMeta
import io.github.takahirom.clijvm.util.recordingMetaPath
import io.github.takahirom.clijvm.util.writeRecordingMeta
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingMetaAndListTest {

    @Test
    fun `short display names are not truncated`() {
        val (text, truncated) = truncateDisplayName("com.example.Main", full = false)
        assertEquals("com.example.Main", text)
        assertFalse(truncated)
    }

    @Test
    fun `long display names are truncated with an ellipsis unless full`() {
        val long = "x".repeat(MAX_DISPLAY_NAME + 50)
        val (text, truncated) = truncateDisplayName(long, full = false)
        assertTrue(truncated)
        assertEquals(MAX_DISPLAY_NAME + 1, text.length) // MAX chars + the ellipsis
        assertTrue(text.endsWith("…"))

        val (fullText, fullTruncated) = truncateDisplayName(long, full = true)
        assertEquals(long, fullText)
        assertFalse(fullTruncated)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `recording meta round-trips and tolerates a missing sidecar`() {
        val dir = Files.createTempDirectory("clijvm-meta")
        try {
            val recording = dir.resolve("20260101-120000-4242.jfr")
            Files.writeString(recording, "not a real jfr")

            assertNull(readRecordingMeta(recording), "no sidecar yet")

            val meta = RecordingMeta(pid = 4242, mainClass = "com.example.\"Main\"", startedAt = 1_700_000_000_000, partial = true)
            writeRecordingMeta(recording, meta)
            assertTrue(Files.exists(recordingMetaPath(recording)))

            val read = readRecordingMeta(recording)
            assertEquals(meta, read)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `drill guidance branches on digest mode`() {
        val nonDigest = drillGuidance("/tmp/rec.jfr", digest = false)
        assertTrue(nonDigest.contains("--method N (see #N above)"), nonDigest)
        assertFalse(nonDigest.contains("first to get #N indices"), nonDigest)

        val digest = drillGuidance("/tmp/rec.jfr", digest = true)
        // Digest has no #N indices yet, so it must not claim they were printed above.
        assertFalse(digest.contains("see #N above"), digest)
        assertTrue(digest.contains("first to get #N indices"), digest)
        assertTrue(digest.contains("--method N / --site N / --thread N"), digest)
    }

    @Test
    fun `report list main-class cell truncates and falls back to unknown`() {
        assertEquals("(unknown)", recordingMainClassCell(null))
        assertEquals("com.example.Main", recordingMainClassCell("com.example.Main"))
        val long = "x".repeat(MAX_DISPLAY_NAME + 50)
        val cell = recordingMainClassCell(long)
        assertTrue(cell.endsWith("…"), cell)
        assertEquals(MAX_DISPLAY_NAME + 1, cell.length)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `corrupt sidecar is ignored rather than throwing`() {
        val dir = Files.createTempDirectory("clijvm-meta-corrupt")
        try {
            val recording = dir.resolve("rec.jfr")
            Files.writeString(recording, "jfr")
            Files.writeString(recordingMetaPath(recording), "{ this is not json")
            assertNull(readRecordingMeta(recording))
        } finally {
            dir.deleteRecursively()
        }
    }
}
