package io.github.takahirom.clijvm.session

import io.github.takahirom.clijvm.util.JsonParser
import io.github.takahirom.clijvm.util.clijvmHome
import io.github.takahirom.clijvm.util.jsonInt
import io.github.takahirom.clijvm.util.jsonObject
import io.github.takahirom.clijvm.util.jsonString
import io.github.takahirom.clijvm.util.jsonStringOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** A session file's content: either a valid [Session] or an unparseable file. */
sealed interface SessionEntry {
    data class Valid(val session: Session) : SessionEntry
    data class Corrupt(val path: Path, val reason: String) : SessionEntry
}

/**
 * Reads and writes background-recording session files under `~/.clijvm/sessions/<pid>.json`.
 */
class SessionStore(private val directory: Path = clijvmHome.resolve("sessions")) {

    private fun fileFor(pid: Long): Path = directory.resolve("$pid.json")

    fun exists(pid: Long): Boolean = Files.exists(fileFor(pid))

    fun save(session: Session) {
        Files.createDirectories(directory)
        val json = jsonObject(
            "pid" to jsonInt(session.pid),
            "recordingName" to jsonString(session.recordingName),
            "startEpochMs" to jsonInt(session.startEpochMs),
            "mainClass" to jsonStringOrNull(session.mainClass),
            "dumpOnExitPath" to jsonStringOrNull(session.dumpOnExitPath),
        ).render()
        Files.writeString(fileFor(session.pid), json)
    }

    /** Returns the session for [pid], or null if there is no session file. Throws on a corrupt file. */
    fun find(pid: Long): Session? {
        val file = fileFor(pid)
        if (!Files.exists(file)) return null
        return read(file)
    }

    fun delete(pid: Long) {
        Files.deleteIfExists(fileFor(pid))
    }

    fun deleteFile(path: Path) {
        Files.deleteIfExists(path)
    }

    /** Lists every session file, tagging each as valid or corrupt. */
    fun list(): List<SessionEntry> {
        if (!Files.isDirectory(directory)) return emptyList()
        return directory.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "json" }
            .sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
            .map { path ->
                try {
                    SessionEntry.Valid(read(path))
                } catch (e: Exception) {
                    SessionEntry.Corrupt(path, e.message ?: "unparseable session file")
                }
            }
    }

    private fun read(file: Path): Session {
        val map = JsonParser.parse(Files.readString(file)) as? Map<*, *>
            ?: throw IllegalArgumentException("session file is not a JSON object")
        val pid = (map["pid"] as? Number)?.toLong()
            ?: throw IllegalArgumentException("missing or invalid 'pid'")
        val recordingName = map["recordingName"] as? String
            ?: throw IllegalArgumentException("missing or invalid 'recordingName'")
        val startEpochMs = (map["startEpochMs"] as? Number)?.toLong()
            ?: throw IllegalArgumentException("missing or invalid 'startEpochMs'")
        val mainClass = map["mainClass"] as? String
        val dumpOnExitPath = map["dumpOnExitPath"] as? String
        return Session(pid, recordingName, startEpochMs, mainClass, dumpOnExitPath)
    }
}
