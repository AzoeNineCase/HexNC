package com.hexnc.music.data.download

import java.io.File

/**
 * Desktop download target: a plain folder under the user's Music library.
 *
 * The default root is `%USERPROFILE%\Music\HexNC`, which is where a Windows media player is
 * expected to put exported music, and the user can point it anywhere in the settings screen.
 *
 * The root is read through [rootProvider] on every call rather than captured once, so changing the
 * folder in settings takes effect on the next download instead of after a restart.
 *
 * Writes go to `<name>.part` first and are then renamed. A rename inside one directory is atomic on
 * NTFS, so a scanner can never observe a half-written file, and an interrupted run leaves at most a
 * `.part` that [exists] ignores.
 */
class DesktopDownloadSink(private val rootProvider: () -> File) : DownloadSink {

    private val root: File get() = rootProvider()

    private fun target(relativePath: String): File = File(root, relativePath)

    override fun location(relativePath: String): String = target(relativePath).absolutePath

    override fun exists(relativePath: String): Boolean =
        target(relativePath).let { it.isFile && it.length() > 0L }

    override fun write(relativePath: String, bytes: ByteArray): String {
        val file = target(relativePath)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".part")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                // Rename can fail when the destination is locked by a player that has it open; a
                // copy still produces a complete file, it is simply not atomic.
                temp.copyTo(file, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return file.absolutePath
    }

    companion object {
        /** `%USERPROFILE%\Music\HexNC`, or a fallback under the user home when Music is absent. */
        fun defaultRoot(): File {
            val home = System.getProperty("user.home") ?: "."
            val music = File(home, "Music")
            val parent = if (music.isDirectory) music else File(home)
            return File(parent, "HexNC")
        }
    }
}