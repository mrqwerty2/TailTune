package dev.tailtune.remote

/** Prevents Subsonic authentication parameters and long internal details reaching the UI. */
object ErrorSanitizer {
    private val querySecret = Regex(
        "([?&](?:u|p|t|s|token|password)=)[^&\\s]+",
        RegexOption.IGNORE_CASE
    )
    private val authorizationHeader = Regex(
        "(authorization\\s*[:=]\\s*)[^,;\\r\\n]+",
        RegexOption.IGNORE_CASE
    )
    private val sensitiveHeader = Regex(
        "((?:x-tailtune-token|password)\\s*[:=]\\s*)[^,;\\s]+",
        RegexOption.IGNORE_CASE
    )

    fun message(error: Throwable, maxLength: Int = 300): String {
        val root = rootCause(error)
        return message(root.message ?: root.javaClass.simpleName, maxLength)
    }

    fun message(value: String, maxLength: Int = 300): String = value
        .replace(querySecret, "$1<redacted>")
        .replace(authorizationHeader, "$1<redacted>")
        .replace(sensitiveHeader, "$1<redacted>")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
        .ifBlank { "Unknown error" }
        .take(maxLength.coerceAtLeast(0))

    fun rootCause(error: Throwable): Throwable {
        var current = error
        repeat(MAX_CAUSE_DEPTH) {
            val next = current.cause ?: return current
            if (next === current) return current
            current = next
        }
        return current
    }

    private const val MAX_CAUSE_DEPTH = 32
}
