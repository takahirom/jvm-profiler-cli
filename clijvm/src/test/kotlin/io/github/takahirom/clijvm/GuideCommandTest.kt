package io.github.takahirom.clijvm

import com.github.ajalt.clikt.testing.test
import io.github.takahirom.clijvm.cli.GuideCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideCommandTest {

    private val topics = listOf("jvm-test", "server", "build", "short-lived", "reading")

    @Test
    fun `no topic prints an index listing every topic`() {
        val result = GuideCommand().test("")
        assertEquals(0, result.statusCode, result.output)
        for (topic in topics) {
            assertTrue(result.output.contains(topic), "index should mention '$topic':\n${result.output}")
        }
    }

    @Test
    fun `every topic renders a non-empty playbook`() {
        for (topic in topics) {
            val result = GuideCommand().test(topic)
            assertEquals(0, result.statusCode, "topic '$topic' should succeed:\n${result.output}")
            assertTrue(result.output.contains("guide: $topic"), "topic '$topic' header missing:\n${result.output}")
        }
    }

    @Test
    fun `situational topics point back at the reading guide`() {
        for (topic in listOf("jvm-test", "server", "build", "short-lived")) {
            val result = GuideCommand().test(topic)
            assertTrue(
                result.output.contains("clijvm guide reading"),
                "topic '$topic' should reference the reading guide:\n${result.output}",
            )
        }
    }

    @Test
    fun `an unknown topic fails and lists the valid topics`() {
        val result = GuideCommand().test("does-not-exist")
        assertTrue(result.statusCode != 0, "unknown topic should be a non-zero exit:\n${result.output}")
        for (topic in topics) {
            assertTrue(result.output.contains(topic), "error should list '$topic':\n${result.output}")
        }
    }
}
