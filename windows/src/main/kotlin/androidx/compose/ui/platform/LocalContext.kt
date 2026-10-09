package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Desktop stand-in for the Android `LocalContext`.
 *
 * Compose UI files that only need "the application object" (`ui/components/Common.kt`,
 * `ui/screens/SignInScreen.kt`) keep `LocalContext.current` unchanged; on Windows the value is a
 * [com.hexnc.music.HexNCApp], which is also an `android.content.Context`, so
 * `(context.applicationContext as HexNCApp).container` resolves to the same `AppContainer` the
 * Android build used.
 *
 * Provided once at the root of the composition in `Main.kt`.
 */
val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf {
    error(
        "No android.content.Context provided. Wrap the UI in " +
            "CompositionLocalProvider(LocalContext provides appContext) { … }.",
    )
}
