package com.gtstore

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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

data class PkgCaptureResult(
    val sourceUrl: String,
    val directUrl: String,
    val fileName: String
)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val AD_BLOCK_KEYWORDS = listOf(
    "doubleclick", "adservice", "popads", "popcash", "propellerads",
    "adsterra", "exoclick", "bet365", "betano", "blaze", "1xbet",
    "shorte.st", "adf.ly", "ouo.io", "trafficjunky", "syndication",
    "onclickprediction", "clickadu", "histats", "track.", "leonbet",
    "bcgameloop", "zzztrack", "mob-trk", "rollerads"
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
    // Armazena a última página web legítima onde o usuário realmente navegou
    var lastValidWebPageUrl by remember { mutableStateOf(sourceUrl) }
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

    // Identifica se é uma página web navegável (e não o arquivo binário direto)
    fun isHtmlWebPage(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return !clean.endsWith(".pkg") && 
               !clean.endsWith(".bin") && 
               !isAdOrSpam(url) && 
               (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))
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

        // A página de atualização é com certeza a última página HTML válida exibida
        val pageWhereDownloadTriggered = when {
            isHtmlWebPage(lastValidWebPageUrl) -> lastValidWebPageUrl
            view?.url != null && isHtmlWebPage(view.url!!) -> view.url!!
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
                    settings.setSupportMultipleWindows(false) // Mantém a estabilidade sem travar
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webChromeClient = WebChromeClient()

                    webViewClient = object : WebViewClient() {
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

                            // Se for o arquivo .pkg real fora de páginas web do Filekeeper, captura na hora
                            if (cleanPath.endsWith(".pkg", ignoreCase = true) && !host.contains("filekeeper.net")) {
                                handleCapturedUrl(urlString)
                                return true
                            }

                            currentDisplayUrl = urlString
                            if (isHtmlWebPage(urlString)) {
                                lastValidWebPageUrl = urlString
                            }

                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentDisplayUrl = url
                            if (isHtmlWebPage(url)) {
                                lastValidWebPageUrl = url
                            }
                            canGoBack = view.canGoBack()

                            if (!processingCapture) {
                                status = "Página pronta. Clique no download."
                            }
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
