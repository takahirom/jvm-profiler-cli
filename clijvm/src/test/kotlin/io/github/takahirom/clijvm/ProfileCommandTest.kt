package io.github.takahirom.clijvm

import com.github.ajalt.clikt.testing.test
import io.github.takahirom.clijvm.cli.ProfileCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileCommandTest {

    @Test
    fun `help exposes the full option surface`() {
        val result = ProfileCommand().test("--help")
        assertEquals(0, result.statusCode, result.output)
        for (option in listOf("--duration", "--wait", "--wait-timeout", "--format", "--output", "--digest", "--top", "--max-stack-depth")) {
            assertTrue(result.output.contains(option), "help should list '$option':\n${result.output}")
        }
    }

    @Test
    fun `with no target and no wait it fails with a clear message`() {
        val result = ProfileCommand().test("")
        assertTrue(result.statusCode != 0, "missing target should be a non-zero exit:\n${result.output}")
        assertTrue(
            result.output.contains("target") && result.output.contains("--wait"),
            "error should tell the user to provide a target or --wait:\n${result.output}",
        )
    }
}
