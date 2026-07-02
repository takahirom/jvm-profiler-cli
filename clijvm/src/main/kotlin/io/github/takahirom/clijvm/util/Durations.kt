package io.github.takahirom.clijvm.util

import java.time.Duration

/**
 * Parses a human-friendly duration such as `30s`, `5m`, `500ms`, `2h`, or a plain
 * number that is interpreted as seconds (`30` == `30s`).
 *
 * @throws IllegalArgumentException if the text is empty or does not match a supported form.
 */
fun parseDuration(text: String): Duration {
    val normalized = text.trim().lowercase()
    require(normalized.isNotEmpty()) { "Duration must not be empty" }
    val match = Regex("^(\\d+)(ms|s|m|h)?$").matchEntire(normalized)
        ?: throw IllegalArgumentException(
            "Invalid duration: '$text' (expected forms like 30s, 5m, 500ms, 2h, or plain seconds)"
        )
    val value = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "ms" -> Duration.ofMillis(value)
        "m" -> Duration.ofMinutes(value)
        "h" -> Duration.ofHours(value)
        else -> Duration.ofSeconds(value) // "s" or an empty unit
    }
}
