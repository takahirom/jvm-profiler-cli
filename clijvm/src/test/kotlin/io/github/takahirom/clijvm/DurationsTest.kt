package io.github.takahirom.clijvm

import io.github.takahirom.clijvm.util.parseDuration
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurationsTest {
    @Test
    fun `parses plain seconds`() {
        assertEquals(Duration.ofSeconds(30), parseDuration("30"))
    }

    @Test
    fun `parses explicit units`() {
        assertEquals(Duration.ofSeconds(30), parseDuration("30s"))
        assertEquals(Duration.ofMinutes(5), parseDuration("5m"))
        assertEquals(Duration.ofMillis(500), parseDuration("500ms"))
        assertEquals(Duration.ofHours(2), parseDuration("2h"))
    }

    @Test
    fun `is case and whitespace insensitive`() {
        assertEquals(Duration.ofMinutes(5), parseDuration("  5M "))
    }

    @Test
    fun `rejects invalid input`() {
        assertFailsWith<IllegalArgumentException> { parseDuration("") }
        assertFailsWith<IllegalArgumentException> { parseDuration("abc") }
        assertFailsWith<IllegalArgumentException> { parseDuration("10x") }
        assertFailsWith<IllegalArgumentException> { parseDuration("1.5s") }
    }
}
