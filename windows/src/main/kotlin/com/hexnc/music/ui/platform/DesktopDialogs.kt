package com.hexnc.music.ui.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser

/**
 * The desktop replacements for Android's activity-result pickers
 * (`ActivityResultContracts.OpenDocumentTree` / `GetContent`).
 *
 * The Android screens had to go through a permission dance because a phone sandboxes the filesystem;
 * Windows does not, so an import is just "which folder or which files". These dialogs are modal and
 * are called straight from a Compose click handler, which runs on the AWT event thread — the same
 * thread Swing and AWT dialogs must be created on.
 *
 * They return plain [File]s: `LocalScanner` on desktop takes real paths rather than `content://` URIs.
 */
internal object DesktopDialogs {

    /** `OpenDocumentTree` equivalent. */
    fun chooseDirectory(title: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /**
     * `GetContent` equivalent, multi-select enabled.
     *
     * [extensions] is a lowercase set without dots (`setOf("mp3", "flac")`); empty means "any file".
     */
    fun chooseFiles(title: String, extensions: Set<String> = emptySet()): List<File> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            isMultipleMode = true
            if (extensions.isNotEmpty()) {
                filenameFilter = java.io.FilenameFilter { _, name ->
                    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
                }
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val names = dialog.files?.map { it.name }.orEmpty().ifEmpty {
            dialog.file?.let { listOf(it) }.orEmpty()
        }
        if (dir.isNullOrBlank() || names.isEmpty()) return emptyList()
        return names.map { File(dir, it) }
    }

    /** Single-selection variant used by the cover and lyric pickers. */
    fun chooseFile(title: String, extensions: Set<String> = emptySet()): File? =
        chooseFiles(title, extensions).firstOrNull()

    /** What `LocalScanner.AUDIO_EXTENSIONS` accepts, for the import dialog's filter. */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "mp4", "3gp",
        // DSD is listed so the files can be imported and listed; the transport refuses to hand them
        // to Java Sound and says so instead of failing with a decoder error.
        "dsf", "dff",
    )

    val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
}
