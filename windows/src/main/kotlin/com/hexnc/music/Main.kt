package com.hexnc.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hexnc.music.ui.HexNCRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop entry point.
 *
 * The Android build got its object graph from `Application` and its window from an Activity; here
 * both come from `main`. The graph is built lazily by [HexNCApp], so the window appears before the
 * database, the media cache and the cookie store are touched, and [AppContainer.warmUp] then runs
 * on the coroutine dispatcher rather than the UI thread — first paint must not wait on disk.
 *
 * The system look and feel is installed because the file and folder pickers are real Swing/AWT
 * dialogs: without it a Windows folder chooser renders in Metal and looks like a different program.
 *
 * Desktop shortcuts: space toggles playback, Ctrl+←/→ skip tracks, Ctrl+↑/↓ nudge the volume and
 * Ctrl+M mutes. `onKeyEvent` runs after the focused component, so a space typed into the search
 * field still inserts a space — nothing here steals a character a text field wanted.
 */
fun main() {
    runCatching {
        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
    }

    val app = HexNCApp(HexNCApp.defaultDataDir())

    // A packaged GUI app has no console, so an uncaught exception would otherwise just make the
    // window vanish. This happened for real: the first app image died because the bundled runtime
    // was missing `java.sql`, and the only trace was stderr nobody could see. Now the stack goes to
    // a log file next to the database and the user is told where to find it.
    val crashLog = java.io.File(app.dataDir, "crash.log")
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        runCatching {
            crashLog.appendText("\n===== ${java.time.Instant.now()} =====\n" + error.stackTraceToString())
        }
        val message = "HexNC 遇到了一个错误，详情已写入：\n$crashLog\n\n" +
            "HexNC hit an error; details were written to:\n$crashLog\n\n$error"
        runCatching {
            javax.swing.SwingUtilities.invokeLater {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    message,
                    "HexNC",
                    javax.swing.JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    application {
        Window(
            onCloseRequest = {
                app.shutdown()
                exitApplication()
            },
            title = "HexNC",
            state = rememberWindowState(size = DpSize(1180.dp, 780.dp)),
            onKeyEvent = { event ->
                // Media keys are owned by the OS on Windows, but the usual desktop shortcuts are
                // not: `onKeyEvent` runs after the focused component has had its turn, so typing a
                // space into a search field still inserts a space and only an unhandled press
                // toggles playback.
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when {
                        event.key == Key.Spacebar -> {
                            app.container.playback.toggle()
                            true
                        }

                        event.isCtrlPressed && event.key == Key.DirectionRight -> {
                            app.container.playback.next()
                            true
                        }

                        event.isCtrlPressed && event.key == Key.DirectionLeft -> {
                            app.container.playback.previous()
                            true
                        }

                        event.isCtrlPressed && event.key == Key.DirectionUp -> {
                            app.container.settings.setVolume(app.container.settings.current.volume + 0.05f)
                            true
                        }

                        event.isCtrlPressed && event.key == Key.DirectionDown -> {
                            app.container.settings.setVolume(app.container.settings.current.volume - 0.05f)
                            true
                        }

                        event.isCtrlPressed && event.key == Key.M -> {
                            app.container.settings.setVolume(
                                if (app.container.settings.current.volume > 0f) 0f else 1f,
                            )
                            true
                        }

                        else -> false
                    }
                }
            },
        ) {
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { app.container.warmUp() }
            }
            CompositionLocalProvider(LocalContext provides app) {
                HexNCRoot(app.container)
            }
        }
    }
}
