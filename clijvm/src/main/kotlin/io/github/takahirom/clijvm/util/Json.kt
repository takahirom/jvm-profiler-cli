package io.github.takahirom.clijvm.util

import java.util.Locale

/**
 * Minimal hand-rolled JSON model with correct string escaping and pretty printing.
 * We avoid an external serialization dependency to keep the tool lightweight.
 */
sealed interface Json {
    data class Obj(val entries: List<Pair<String, Json>>) : Json
    data class Arr(val items: List<Json>) : Json
    data class Str(val value: String) : Json

    /** A pre-formatted literal token, used for numbers and booleans. */
    data class Literal(val token: String) : Json

    fun render(indent: Int = 0): String = StringBuilder().also { write(it, indent) }.toString()

    private fun write(sb: StringBuilder, indent: Int) {
        when (this) {
            is Str -> sb.append(quote(value))
            is Literal -> sb.append(token)
            is Arr -> {
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                items.forEachIndexed { i, item ->
                    sb.append(pad(indent + 1))
                    item.write(sb, indent + 1)
                    if (i != items.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append(pad(indent)).append(']')
            }
            is Obj -> {
                if (entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                entries.forEachIndexed { i, (key, value) ->
                    sb.append(pad(indent + 1)).append(quote(key)).append(": ")
                    value.write(sb, indent + 1)
                    if (i != entries.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append(pad(indent)).append('}')
            }
        }
    }

    companion object {
        private fun pad(indent: Int) = "  ".repeat(indent)

        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

fun jsonString(value: String): Json = Json.Str(value)
fun jsonStringOrNull(value: String?): Json = value?.let { Json.Str(it) } ?: Json.Literal("null")
fun jsonInt(value: Long): Json = Json.Literal(value.toString())
fun jsonInt(value: Int): Json = Json.Literal(value.toString())
fun jsonBool(value: Boolean): Json = Json.Literal(value.toString())

/** Formats a double with up to [decimals] places, using a locale-independent decimal point. */
fun jsonNumber(value: Double, decimals: Int = 2): Json {
    val formatted = String.format(Locale.US, "%.${decimals}f", value)
    val trimmed = if (formatted.contains('.')) formatted.trimEnd('0').trimEnd('.') else formatted
    return Json.Literal(trimmed)
}

fun jsonObject(vararg entries: Pair<String, Json>): Json = Json.Obj(entries.toList())
fun jsonArray(items: List<Json>): Json = Json.Arr(items)
