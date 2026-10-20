package dev.cint.core.artifact

object Json {
    fun escape(s: String): String =
        buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }

    fun render(value: Any?): String = buildString { write(value) }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> {
                append("null")
            }

            is String -> {
                append('"').append(escape(value)).append('"')
            }

            is Boolean -> {
                append(value)
            }

            is Double -> {
                if (value.isFinite()) append(value) else append('"').append(value).append('"')
            }

            is Float -> {
                if (value.isFinite()) append(value) else append('"').append(value).append('"')
            }

            is Number -> {
                append(value)
            }

            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    append('"').append(escape(key.toString())).append('"').append(':')
                    write(item)
                }
                append('}')
            }

            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }

            else -> {
                append('"').append(escape(value.toString())).append('"')
            }
        }
    }
}
