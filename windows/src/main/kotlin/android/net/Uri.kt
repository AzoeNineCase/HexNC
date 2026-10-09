package android.net

import java.io.File

/**
 * Stand-in for `android.net.Uri` limited to what this app does with a URI: build one from a file or
 * a string, print it, and read its path.
 *
 * Keeping the name means `data/local/CoverStore.kt` and `data/repo/LibraryRepository.kt` keep their
 * Android signatures; the desktop file pickers hand back a `java.io.File`, which becomes a `file:`
 * URI here and a real path again inside `CoverStore`.
 */
class Uri private constructor(private val value: String) {

    val scheme: String? get() = value.substringBefore(':', "").ifBlank { null }

    /** Filesystem path for a `file:` URI, or the raw value when it is already a plain path. */
    val path: String?
        get() = when {
            value.startsWith("file:") -> runCatching {
                java.net.URI(value).path?.let(::stripDriveSlash)
            }.getOrNull()

            value.contains("://") -> null
            else -> value
        }

    val lastPathSegment: String? get() = path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(value)

        @JvmStatic
        fun fromFile(file: File): Uri = Uri(file.toURI().toString())

        private fun stripDriveSlash(path: String): String =
            if (path.length > 2 && path[0] == '/' && path[2] == ':') path.substring(1) else path
    }
}
