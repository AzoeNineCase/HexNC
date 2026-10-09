package com.hexnc.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.netease.parseCookieInput
import com.hexnc.music.ui.i18n.Strings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The desktop web client's sign-in route.
 *
 * The page is a hash route, so a browser lands on it directly; the mobile page (`/m/login`) the
 * Android build loads is a stripped-down layout that a PC browser renders badly, and the cookie it
 * sets is the same either way.
 */
private const val LOGIN_URL = "https://music.163.com/#/login"

/**
 * Signs in by letting the user authenticate in their own browser, then importing the session.
 *
 * This is the only route that does not go through the login endpoints (`/api/login/...`), which is
 * exactly the surface NetEase's risk control guards: QR creation and password posts both answer
 * `-462` on a flagged network. On Android the panel embedded a `WebView` and polled its cookie jar;
 * on the desktop there is no `WebView` to embed, so the panel opens the real browser instead — a
 * strictly better sign-in anyway, because the browser is what NetEase expects to see, it can hold
 * 2FA and captcha state, and it is already signed in for most users.
 *
 * The panel therefore has two steps: open the page, then paste the session back. Importing goes
 * through [parseCookieInput], the same parser the "paste a cookie" tab uses, so both routes accept
 * exactly the same text — a bare `MUSIC_U` value or a whole `Cookie:` header.
 */
@Composable
fun BrowserLoginPanel(
    strings: Strings,
    importing: Boolean,
    error: String?,
    onCookie: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pasted by remember { mutableStateOf(TextFieldValue("")) }
    var missing by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = strings.browserTitle,
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(6.dp))
        Caption(strings.browserHint, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            Button(onClick = ::openSignInPage, enabled = !importing) {
                Text(text = strings.browserOpenAction, style = MiuixTheme.textStyles.button)
            }
        }

        Spacer(Modifier.height(18.dp))
        TextField(
            value = pasted,
            onValueChange = {
                pasted = it
                missing = false
            },
            label = strings.cookieLabel,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        Caption(strings.browserPasteHint, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            Button(
                onClick = {
                    val pairs = parseCookieInput(pasted.text)
                    if (pairs.any { it.startsWith("MUSIC_U=") }) {
                        missing = false
                        onCookie(pairs.joinToString("; "))
                    } else {
                        missing = true
                    }
                },
                enabled = !importing && pasted.text.isNotBlank(),
            ) {
                Text(
                    text = if (importing) strings.cookieImporting else strings.browserImportAction,
                    style = MiuixTheme.textStyles.button,
                )
            }
            if (importing) {
                Spacer(Modifier.size(10.dp))
                InfiniteProgressIndicator(modifier = Modifier.align(Alignment.CenterVertically).size(20.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (missing) Caption(strings.browserCookieMissing)
            if (error != null) Caption(strings.cookieFailed.format(error))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Hands [LOGIN_URL] to the default browser.
 *
 * `Desktop.isDesktopSupported()` is checked because a headless or service session reports no
 * desktop at all, and `browse` throws rather than returning a flag — a failed open is not worth
 * interrupting the user over, since the paste field below works without it.
 */
private fun openSignInPage() {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(LOGIN_URL))
        }
    }
}
