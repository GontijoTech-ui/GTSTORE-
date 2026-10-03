package com.gtstore

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

data class PkgCaptureResult(
    val sourceUrl: String,
    val directUrl: String,
    val fileName: String
)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

// Termos comuns em URLs de anúncios agressivos, cassinos e rastreadores
private val AD_BLOCK_KEYWORDS = listOf(
    "doubleclick", "adservice", "popads", "popcash", "propellerads",
    "adsterra", "exoclick", "bet365", "betano", "blaze", "1xbet",
    "shorte.st", "adf.ly", "ouo.io", "trafficjunky", "syndication",
    "onclickprediction", "clickadu", "histats", "track.", "analytics",
    "leonbet", "bcgameloop", "adrun", "banner", "cpm", "ads."
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkgLinkCaptureScreen(
    sourceUrl: String,
    allowedDomains: List<String> = emptyList(),
    onCaptured: (PkgCaptureResult) -> Unit,
    onCancel: () -> Unit
) {
    var currentDisplayUrl by remember { mutableStateOf(sourceUrl) }
    var status by remember { mutableStateOf("Navegue até o download desejado.") }
    var processingCapture by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
        }
    }

    fun isAdOrSpam(url: String): Boolean {
        val lower = url.lowercase()
        return AD_BLOCK_KEYWORDS.any { lower.contains(it) }
    }

    fun voltarParaAntepenultimaSuperPsx(view: WebView) {
        val history = view.copyBackForwardList()
        val currentIndex = history.currentIndex

        var countSuperPsx = 0
        var targetStep = 0

        for (i in currentIndex - 1 downTo 0) {
            val item = history.getItemAtIndex(i)
            val itemUrl = item.url.lowercase()

            if (itemUrl.contains("superpsx.com") && !itemUrl.endsWith(".pkg")) {
                countSuperPsx++
                if (countSuperPsx == 2) {
                    targetStep = i - currentIndex
                    break
                }
            }
        }

        if (targetStep < 0) {
            AppLogger.log("[PkgLinkCaptureScreen] Saltando $targetStep passos para a antepenúltima página SuperPSX.")
            view.goBackOrForward(targetStep)
        } else {
            if (view.canGoBack()) {
                view.goBack()
            } else {
                view.loadUrl(sourceUrl)
            }
        }
    }

    fun handleCapturedUrl(url: String, contentDisposition: String? = null, mimeType: String? = null) {
        if (processingCapture) return
        processingCapture = true

        val isHttp = url.startsWith("http://", ignoreCase = true)
        val isHttps = url.startsWith("https://", ignoreCase = true)
        if (!isHttp && !isHttps) {
            processingCapture = false
            return
        }

        val view = browser
        view?.stopLoading()

        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val finalFileName = guessedFileName.trim().ifBlank { "download.pkg" }

        val webViewUrl = view?.url?.trim() ?: ""
        val pageWhereDownloadTriggered = when {
            webViewUrl.isNotBlank() && !webViewUrl.substringBefore("?").endsWith(".pkg", ignoreCase = true) -> webViewUrl
            currentDisplayUrl.isNotBlank() && !currentDisplayUrl.substringBefore("?").endsWith(".pkg", ignoreCase = true) -> currentDisplayUrl
            else -> sourceUrl
        }

        status = "PKG capturado! Gravando link e voltando à antepenúltima página..."
        AppLogger.log("[PkgLinkCaptureScreen] Download disparado a partir da página: $pageWhereDownloadTriggered")
        AppLogger.log("[PkgLinkCaptureScreen] Link direto capturado: $url")

        onCaptured(
            PkgCaptureResult(
                sourceUrl = pageWhereDownloadTriggered,
                directUrl = url,
                fileName = finalFileName
            )
        )

        view?.postDelayed({
            voltarParaAntepenultimaSuperPsx(view)
            status = "Retornado! Pronto para o próximo link."
            processingCapture = false
        }, 1000)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBlack),
            border = BorderStroke(width = 1.dp, color = BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "CAPTURA DE LINK", color = TextWhite)
                Text(
                    text = status, 
                    color = if (processingCapture) GreenLed else TextMuted
                )

                if (currentDisplayUrl.isNotBlank()) {
                    Text(
                        text = currentDisplayUrl,
                        color = Color(0xFF64B5F6),
                        maxLines = 1
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val view = browser
                    if (view != null && view.canGoBack()) {
                        view.goBack()
                    }
                },
                enabled = canGoBack,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF303030),
                    disabledContainerColor = Color(0xFF181818),
                    contentColor = Color.White,
                    disabledContentColor = Color(0xFF666666)
                )
            ) {
                Text(text = "VOLTAR PÁGINA")
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF303030),
                    contentColor = Color.White
                )
            ) {
                Text(text = "CONCLUIR / SAIR")
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    browser = this

                    settings.userAgentString = BROWSER_USER_AGENT
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    // DESLIGADO para impedir o congelamento com popups parasitas
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webChromeClient = WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        // 1. Intercepta e mata requisições de anúncios pela raiz (economiza CPU/RAM e evita congelar)
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            if (isAdOrSpam(reqUrl)) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 2. Controla o redirecionamento
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val urlString = request.url.toString()

                            if (processingCapture) return true

                            val scheme = request.url.scheme?.lowercase() ?: ""
                            if (scheme != "http" && scheme != "https") {
                                AppLogger.log("[PkgLinkCaptureScreen] Esquema externo evitado: $urlString")
                                return true
                            }

                            if (isAdOrSpam(urlString)) {
                                AppLogger.log("[PkgLinkCaptureScreen] Propaganda bloqueada: $urlString")
                                return true
                            }

                            val cleanPath = urlString.substringBefore("?")
                            val host = request.url.host?.lowercase() ?: ""

                            if (cleanPath.endsWith(".pkg", ignoreCase = true) && !host.contains("filekeeper.net")) {
                                handleCapturedUrl(urlString)
                                return true
                            }

                            currentDisplayUrl = urlString
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentDisplayUrl = url
                            canGoBack = view.canGoBack()

                            if (!processingCapture) {
                                status = "Página carregada."
                            }

                            // Script cirúrgico: remove camadas invisíveis e desativa window.open para não travar
                            val antiAdOverlayScript = """
                                (function() {
                                    // Bloqueia tentativas de scripts abrirem abas à força
                                    window.open = function() { return null; };

                                    // Remove iframes suspeitos e camadas transparentes por cima da tela
                                    const elements = document.querySelectorAll('iframe, div, a');
                                    elements.forEach(el => {
                                        const style = window.getComputedStyle(el);
                                        const zIndex = parseInt(style.zIndex, 10);
                                        // Detecta divs transparentes que ocupam a tela inteira (clickjack)
                                        if (zIndex > 1000 && (style.position === 'fixed' || style.position === 'absolute') && style.opacity === '0') {
                                            el.remove();
                                        }
                                    });

                                    // Clica no verificador legítimo se disponível
                                    const captchaSelectors = [
                                        '#recaptcha-anchor',
                                        '#checkbox',
                                        'input[type="checkbox"]',
                                        '.cf-turnstile input',
                                        '.cf-turnstile',
                                        '#amzn-captcha-verify-button'
                                    ];
                                    
                                    for (let sel of captchaSelectors) {
                                        let el = document.querySelector(sel);
                                        if (el && el.offsetParent !== null) {
                                            el.click();
                                            return;
                                        }
                                    }
                                })();
                            """.trimIndent()

                            view.postDelayed({
                                view.evaluateJavascript(antiAdOverlayScript, null)
                            }, 500)
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                        AppLogger.log("[PkgLinkCaptureScreen] DownloadListener disparado: $url")
                        handleCapturedUrl(url, contentDisposition, mimeType)
                    }

                    loadUrl(sourceUrl)
                }
            },
            update = { view ->
                browser = view
                canGoBack = view.canGoBack()
            }
        )
    }
}
