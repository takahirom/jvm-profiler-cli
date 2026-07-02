package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.session.Session
import io.github.takahirom.clijvm.session.SessionEntry
import io.github.takahirom.clijvm.session.SessionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStoreTest {
    private val dir: Path = Files.createTempDirectory("clijvm-sessions")
    private val store = SessionStore(dir)

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `round-trips a session`() {
        val session = Session(
            pid = 4242,
            recordingName = "clijvm-4242",
            startEpochMs = 1_700_000_000_000,
            mainClass = "com.example.App --flag \"quoted value\"",
        )
        store.save(session)
        assertTrue(store.exists(4242))
        assertEquals(session, store.find(4242))
    }

    @Test
    fun `preserves a null main class`() {
        val session = Session(99, "clijvm-99", 123, null)
        store.save(session)
        assertEquals(session, store.find(99))
    }

    @Test
    fun `find returns null when no session exists`() {
        assertNull(store.find(12345))
    }

    @Test
    fun `delete removes the session`() {
        store.save(Session(7, "clijvm-7", 1, null))
        store.delete(7)
        assertNull(store.find(7))
    }

    @Test
    fun `list flags corrupt files`() {
        store.save(Session(1, "clijvm-1", 10, "A"))
        Files.writeString(dir.resolve("2.json"), "{ this is not valid json")
        Files.writeString(dir.resolve("3.json"), """{"recordingName":"x"}""") // missing pid

        val entries = store.list()
        assertEquals(3, entries.size)

        val valid = entries.filterIsInstance<SessionEntry.Valid>()
        val corrupt = entries.filterIsInstance<SessionEntry.Corrupt>()
        assertEquals(1, valid.size)
        assertEquals(1L, valid.single().session.pid)
        assertEquals(2, corrupt.size)
    }
}
