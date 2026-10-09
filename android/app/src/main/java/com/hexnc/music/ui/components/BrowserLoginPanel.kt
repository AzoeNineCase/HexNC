package com.hexnc.music.ui.components

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hexnc.music.ui.i18n.Strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The page NetEase serves to mobile browsers; it is the one the official web client uses. */
private const val LOGIN_URL = "https://music.163.com/m/login"

/** Every host the login flow can leave cookies on. */
private val COOKIE_HOSTS = listOf(
    "https://music.163.com",
    "https://music.163.com/m/login",
    "https://y.music.163.com",
)

/**
 * Reads the WebView cookie jar and flattens it into a single `k=v; k=v` string.
 *
 * [android.webkit.CookieManager.getCookie] answers per URL, and the login flow sets cookies under
 * more than one path and host, so the jars are merged. Later duplicates win, which is what the
 * browser would send too.
 */
private fun harvestCookies(): String {
    val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
    val jar = LinkedHashMap<String, String>()
    for (host in COOKIE_HOSTS) {
        val raw = runCatching { manager.getCookie(host) }.getOrNull() ?: continue
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            jar[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
        }
    }
    return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * Signs in by letting the user authenticate in a real browser engine, then importing the session.
 *
 * This is the only route that does not go through the login endpoints (`/api/login/...`), which is
 * exactly the surface NetEase's risk control guards: QR creation and password posts both answer
 * `-462` on a flagged network. A WebView performs the same page login a browser would, and the
 * resulting `MUSIC_U` cookie is then handed to the normal session import.
 *
 * The jar is polled rather than observed through a [WebViewClient] callback so that the whole panel
 * stays free of overridden platform hooks — the same behaviour, with far less to go wrong across
 * WebView versions.
 */
@Composable
fun BrowserLoginPanel(
    strings: Strings,
    importing: Boolean,
    error: String?,
    onCookie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var missing by remember { mutableStateOf(false) }
    val currentOnCookie by rememberUpdatedState(onCookie)

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(600)
            val view = webView ?: continue
            progress = view.progress
            val raw = harvestCookies()
            if (raw.contains("MUSIC_U=")) {
                runCatching { CookieManager.getInstance().flush() }
                currentOnCookie(raw)
                return@LaunchedEffect
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = strings.browserTitle,
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))
        Caption(strings.browserHint, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        // A plain client instance keeps navigations inside this view. Without one
                        // the platform is free to hand links to the system browser, which would
                        // strand the user in another app mid-login.
                        webViewClient = WebViewClient()
                        val manager = CookieManager.getInstance()
                        manager.setAcceptCookie(true)
                        manager.setAcceptThirdPartyCookies(this, true)
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
                onRelease = { view ->
                    webView = null
                    runCatching {
                        view.stopLoading()
                        view.destroy()
                    }
                },
            )

            if (progress < 100) {
                Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                    InfiniteProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            if (progress < 100) Caption(strings.browserLoading)
            if (missing) Caption(strings.browserCookieMissing)
            if (error != null) Caption(strings.cookieFailed.format(error))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.Start) {
                Button(
                    onClick = {
                        val raw = harvestCookies()
                        if (raw.contains("MUSIC_U=")) {
                            missing = false
                            onCookie(raw)
                        } else {
                            missing = true
                        }
                    },
                    enabled = !importing,
                ) {
                    Text(
                        text = if (importing) strings.cookieImporting else strings.browserImportAction,
                        style = MiuixTheme.textStyles.button,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
