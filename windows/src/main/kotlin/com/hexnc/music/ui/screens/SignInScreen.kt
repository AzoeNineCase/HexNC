package com.hexnc.music.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hexnc.music.data.repo.QrPoll
import com.hexnc.music.data.repo.QrStage
import com.hexnc.music.di.AppContainer
import com.hexnc.music.ui.components.BrowserLoginPanel
import com.hexnc.music.ui.components.Caption
import com.hexnc.music.ui.components.HexList
import com.hexnc.music.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val QR_PIXELS = 720

/**
 * NetEase sign-in: by QR code, by password, through the system browser, or by pasting a session.
 *
 * NetEase's risk control frequently refuses logins from third-party clients. QR creation always
 * succeeds, but the *poll* can come back `-462`, which means "solve a captcha first" rather than a
 * QR state; password logins are stopped the same way (a fake password answers `-462`, not a
 * credential error). So the screen offers four routes and treats the captcha gate as a first-class
 * outcome: the QR tab surfaces the verification page instead of spinning, and the two session
 * routes — a browser sign-in that hands back the session cookie, and a field for pasting a
 * `MUSIC_U` cookie — do not touch the login endpoints at all.
 *
 * The QR bitmap is always drawn black-on-white regardless of the active palette — scanners need
 * the contrast, and an aurora-tinted code is simply unreadable.
 */
@Composable
fun SignInScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var unikey by remember { mutableStateOf<String?>(null) }
    var poll by remember { mutableStateOf(QrPoll.WAITING) }
    var generating by remember { mutableStateOf(true) }
    var qrFailed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var cookieText by remember { mutableStateOf(TextFieldValue("")) }
    var importing by remember { mutableStateOf(false) }
    var cookieError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshTick) {
        generating = true
        qrFailed = false
        poll = QrPoll.WAITING
        val key = runCatching { container.netease.startQrLogin() }.getOrNull()
        unikey = key
        generating = false
        qrFailed = key == null
    }

    LaunchedEffect(unikey, refreshTick) {
        val key = unikey ?: return@LaunchedEffect
        val session = container.netease.awaitQrLogin(key) { poll = it }
        if (session != null) {
            snackbarHostState.showSnackbar(strings.qrSuccess)
            onBack()
        }
    }

    fun submit() {
        val id = account.trim()
        if (id.isEmpty() || password.text.isEmpty()) return
        signingIn = true
        error = null
        scope.launch {
            val result = if (id.contains('@')) {
                container.netease.loginWithEmail(id, password.text)
            } else {
                container.netease.loginWithCellphone(id, password.text)
            }
            signingIn = false
            result
                .onSuccess {
                    snackbarHostState.showSnackbar(strings.qrSuccess)
                    onBack()
                }
                .onFailure { error = it.message ?: strings.wrongCredentials }
        }
    }

    /**
     * Hands a cookie string to the repository.
     *
     * Shared by the paste tab and the in-app browser tab: the browser panel already produces a
     * `k=v; k=v` string, so both routes end up at exactly the same validation.
     */
    fun importRaw(raw: String) {
        if (raw.isBlank()) {
            cookieError = strings.cookieEmpty
            return
        }
        importing = true
        cookieError = null
        scope.launch {
            val result = container.netease.importSessionCookie(raw)
            importing = false
            result
                .onSuccess {
                    snackbarHostState.showSnackbar(strings.qrSuccess)
                    onBack()
                }
                .onFailure { cookieError = it.message ?: strings.cookieEmpty }
        }
    }

    fun importCookie() = importRaw(cookieText.text.trim())

    DetailScaffold(
        title = strings.signIn,
        subtitle = strings.signInSubtitle,
        strings = strings,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            // The tab row lives outside the list: the browser tab is not a list at all, and a
            // picker that scrolls away is the wrong affordance for a screen with four routes.
            TabRow(
                tabs = listOf(
                    strings.signInWithQr,
                    strings.signInWithPassword,
                    strings.signInWithCookie,
                    strings.signInWithBrowser,
                ),
                selectedTabIndex = tab,
                onTabSelected = { tab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            if (tab == 3) {
                BrowserLoginPanel(
                    strings = strings,
                    importing = importing,
                    error = cookieError,
                    onCookie = { raw -> importRaw(raw) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                HexList(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                if (tab == 0) {
                    item(key = "qr") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Caption(strings.qrHint)
                            Spacer(Modifier.height(16.dp))
                            QrPanel(
                                url = unikey?.let { container.netease.qrLoginUrl(it) },
                                generating = generating,
                                failed = qrFailed,
                                stage = poll.stage,
                            )
                            Spacer(Modifier.height(12.dp))
                            val blocked = poll.stage == QrStage.VERIFY_REQUIRED
                            val needsRetry = poll.stage == QrStage.EXPIRED || qrFailed
                            Text(
                                text = when {
                                    qrFailed -> strings.generateQrFailed
                                    blocked -> strings.qrVerifyRequired
                                    poll.stage == QrStage.SCANNED -> strings.qrScanned
                                    poll.stage == QrStage.EXPIRED -> strings.qrExpired
                                    else -> strings.qrWaiting
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (blocked || needsRetry) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onBackgroundVariant
                                },
                            )
                            when {
                                blocked -> {
                                    poll.verifyUrl?.let { url ->
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(
                                            text = strings.qrVerifyAction,
                                            onClick = { openUrl(url) },
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Caption(strings.qrVerifyHint)
                                    Spacer(Modifier.height(4.dp))
                                    TextButton(
                                        text = strings.signInWithCookie,
                                        onClick = { tab = 2 },
                                    )
                                }

                                needsRetry -> TextButton(
                                    text = strings.qrRefresh,
                                    onClick = { refreshTick++ },
                                )
                            }
                        }
                    }
                } else if (tab == 1) {
                    item(key = "credentials") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            TextField(
                                value = account,
                                onValueChange = { account = it },
                                label = strings.emailOrPhone,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            TextField(
                                value = password,
                                onValueChange = { password = it },
                                label = strings.password,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { submit() },
                                    enabled = !signingIn && account.isNotBlank() && password.text.isNotEmpty(),
                                ) {
                                    Text(
                                        text = if (signingIn) strings.signingIn else strings.signInAction,
                                        style = MiuixTheme.textStyles.button,
                                    )
                                }
                                TextButton(
                                    text = strings.signInWithQr,
                                    onClick = { tab = 0 },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Caption(strings.captchaRequired)
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                text = strings.signInWithCookie,
                                onClick = { tab = 2 },
                            )
                            if (error != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = strings.signInFailed.format(error.orEmpty()),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else {
                    item(key = "cookie") {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text(
                                text = strings.cookieTitle,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                            Spacer(Modifier.height(8.dp))
                            Caption(strings.cookieHint)
                            Spacer(Modifier.height(12.dp))
                            TextField(
                                value = cookieText,
                                onValueChange = { cookieText = it },
                                label = strings.cookieLabel,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { importCookie() },
                                enabled = !importing && cookieText.text.isNotBlank(),
                            ) {
                                Text(
                                    text = if (importing) strings.cookieImporting else strings.cookieAction,
                                    style = MiuixTheme.textStyles.button,
                                )
                            }
                            if (cookieError != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = strings.cookieFailed.format(cookieError.orEmpty()),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                item(key = "risk") {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Caption(strings.accountRiskWarning)
                    }
                }

                item(key = "note") {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Caption(strings.disclaimerBody)
                    }
                }
                }
            }
        }
    }
}

/**
 * Draws the sign-in QR code, or the state that replaces it.
 *
 * The bitmap is rebuilt only when the URL changes: encoding is pure CPU work, and the poll loop
 * recomposes this panel several times a second.
 */
@Composable
private fun QrPanel(
    url: String?,
    generating: Boolean,
    failed: Boolean,
    stage: QrStage,
) {
    val bitmap = remember(url) { url?.let { encodeQr(it, QR_PIXELS) } }
    // A blocked session cannot be completed by scanning, so the code is withheld too.
    val dismissed = stage == QrStage.EXPIRED || stage == QrStage.VERIFY_REQUIRED

    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null && !dismissed -> Image(
                bitmap = bitmap.toComposeImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            generating -> top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator(
                modifier = Modifier.size(48.dp),
            )

            else -> Text(
                text = if (failed) "!" else "…",
                style = MiuixTheme.textStyles.title1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Encodes [content] as a QR code.
 *
 * `MARGIN` is one module rather than the library default of four: the panel supplies its own quiet
 * zone, and a smaller margin keeps the modules large enough to scan from a laptop screen.
 *
 * The pixels are assembled by hand in BGRA order because skiko's `N32` surface is little-endian on
 * Windows: passing them as an ARGB `IntArray` (which is what the Android `Bitmap` wanted) swaps red
 * and blue, and a QR code with swapped channels is still *almost* readable — it fails only on
 * scanners that key off the quiet-zone contrast.
 */
private fun encodeQr(content: String, sizePx: Int): SkiaImage? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content,
        com.google.zxing.BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 1,
        ),
    )
    val pixels = ByteArray(sizePx * sizePx * 4)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            val color = if (matrix[x, y]) QR_DARK else QR_LIGHT
            val index = (row + x) * 4
            pixels[index] = (color and 0xFF).toByte()
            pixels[index + 1] = ((color shr 8) and 0xFF).toByte()
            pixels[index + 2] = ((color shr 16) and 0xFF).toByte()
            pixels[index + 3] = ((color shr 24) and 0xFF).toByte()
        }
    }
    SkiaImage.makeRaster(
        ImageInfo(sizePx, sizePx, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
        pixels,
        sizePx * 4,
    )
}.getOrNull()

/**
 * Opens [url] in the user's default browser.
 *
 * The risk-control link points at NetEase's own captcha page, so it has to leave the app: the
 * challenge cannot be solved in-process, and pretending otherwise is what produced the endless
 * spinner this screen used to show. On Windows there is no `Intent` to hand the URL to — the
 * desktop's `Desktop.browse` is the equivalent, and it is guarded because a headless or
 * heavily-locked-down session can report no support at all.
 */
private fun openUrl(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }
}

private const val QR_DARK = 0xFF0B0E17.toInt()
private const val QR_LIGHT = 0xFFFFFFFF.toInt()
