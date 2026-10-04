package com.gtstore

import android.annotation.SuppressLint
import android.net.Uri
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
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkgLinkCaptureScreen(
    sourceUrl: String,
    allowedDomains: List<String> = emptyList(),
    onCaptured: (
        result: PkgCaptureResult,
        onComplete: () -> Unit
    ) -> Unit,
    onCancel: () -> Unit
) {
    var currentDisplayUrl by remember {
        mutableStateOf(sourceUrl)
    }

    var currentPageUrl by remember {
        mutableStateOf(sourceUrl)
    }

    var returnPageUrl by remember {
        mutableStateOf("")
    }

    var originPageUrl by remember {
        mutableStateOf("")
    }

    var status by remember {
        mutableStateOf("Aguardando início do download do PKG...")
    }

    var captured by remember {
        mutableStateOf(false)
    }

    var processing by remember {
        mutableStateOf(false)
    }

    var canGoBack by remember {
        mutableStateOf(false)
    }

    var browser by remember {
        mutableStateOf<WebView?>(null)
    }

    val sourceHost = remember(sourceUrl) {
        try {
            Uri.parse(sourceUrl).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
        }
    }

    fun isDomainPermitted(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Exception) {
            ""
        }

        if (host.isBlank()) {
            return true
        }

        val isSource = sourceHost.isNotEmpty() &&
                (host.contains(sourceHost) || sourceHost.contains(host))

        val isAllowed = allowedDomains.any { domain ->
            val cleanDomain = domain.trim().lowercase()
            cleanDomain.isNotBlank() && host.contains(cleanDomain)
        }

        val isCommonCdn = host.contains("filekeeper") ||
                host.contains("dlproxy") ||
                host.contains("akirabox") ||
                host.contains("mocha")

        return isSource || isAllowed || isCommonCdn
    }

    fun getCurrentBrowserUrl(): String {
        val webViewUrl = browser?.url?.trim().orEmpty()
        return when {
            webViewUrl.isNotBlank() -> webViewUrl
            currentPageUrl.isNotBlank() -> currentPageUrl
            currentDisplayUrl.isNotBlank() -> currentDisplayUrl
            else -> sourceUrl
        }
    }

    fun saveReturnPage() {
        val url = getCurrentBrowserUrl()
        if (url.isBlank()) {
            status = "Não foi possível salvar a página de retorno."
            return
        }
        returnPageUrl = url
        status = "Página de retorno salva."
        AppLogger.log("[PkgLinkCaptureScreen] Página de RETORNO salva: $returnPageUrl")
    }

    fun saveOriginPage() {
        val url = getCurrentBrowserUrl()
        if (url.isBlank()) {
            status = "Não foi possível salvar a página de origem."
            return
        }
        originPageUrl = url
        status = "Página de origem salva."
        AppLogger.log("[PkgLinkCaptureScreen] Página de ORIGEM salva: $originPageUrl")
    }

    fun finishProcessingAndReturn() {
        if (!processing) return

        val targetUrl = returnPageUrl.trim()
        if (targetUrl.isBlank()) {
            processing = false
            captured = false
            returnPageUrl = ""
            originPageUrl = ""
            status = "Aguardando início do download do PKG..."
            return
        }

        status = "Processamento concluído. Voltando à página..."
        val view = browser
        if (view != null) {
            view.post {
                view.loadUrl(targetUrl)
            }
        }

        processing = false
        captured = false
        returnPageUrl = ""
        originPageUrl = ""
        status = "Aguardando início do download do PKG..."
    }

    fun handleCapturedUrl(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null
    ) {
        if (captured || processing) return

        val isHttp = url.startsWith("http://", ignoreCase = true)
        val isHttps = url.startsWith("https://", ignoreCase = true)
        if (!isHttp && !isHttps) return

        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val finalFileName = guessedFileName.trim().ifBlank { "download.pkg" }

        val finalSourceUrl = originPageUrl.trim().ifBlank {
            currentPageUrl.trim().ifBlank { sourceUrl }
        }

        captured = true
        processing = true
        status = "Link PKG capturado. Processando..."

        AppLogger.log("[PkgLinkCaptureScreen] Link capturado com sucesso: $url")
        AppLogger.log("[PkgLinkCaptureScreen] Página de origem utilizada: $finalSourceUrl")

        val result = PkgCaptureResult(
            sourceUrl = finalSourceUrl,
            directUrl = url,
            fileName = finalFileName
        )

        onCaptured(result) {
            finishProcessingAndReturn()
        }
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
            border = BorderStroke(1.dp, BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "CAPTURA DE LINK", color = TextWhite)
                Text(
                    text = status,
                    color = when {
                        processing -> Color(0xFFFFC107)
                        captured -> GreenLed
                        else -> TextMuted
                    }
                )

                if (currentDisplayUrl.isNotBlank()) {
                    Text(text = currentDisplayUrl, color = Color(0xFF64B5F6), maxLines = 1)
                }
                if (returnPageUrl.isNotBlank()) {
                    Text(text = "✓ Página de retorno salva", color = GreenLed, maxLines = 1)
                }
                if (originPageUrl.isNotBlank()) {
                    Text(text = "✓ Página de origem salva", color = GreenLed, maxLines = 1)
                }
            }
        }

        Button(
            onClick = {
                when {
                    returnPageUrl.isBlank() -> saveReturnPage()
                    originPageUrl.isBlank() -> saveOriginPage()
                }
            },
            enabled = !processing && originPageUrl.isBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF303030),
                disabledContainerColor = Color(0xFF181818),
                contentColor = Color.White,
                disabledContentColor = Color(0xFF666666)
            )
        ) {
            Text(
                text = when {
                    returnPageUrl.isBlank() -> "SALVAR PÁGINA DE RETORNO"
                    originPageUrl.isBlank() -> "SALVAR PÁGINA DE ORIGEM"
                    else -> "PÁGINAS SALVAS"
                }
            )
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
                enabled = canGoBack && !processing,
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
                enabled = !processing,
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
                Text(text = "CANCELAR")
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

                    // Configurações vitais para renderização completa do Mocha.my
                    settings.userAgentString = BROWSER_USER_AGENT
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true

                    // Habilita a escala responsiva correta para exibir as listas de arquivos
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

                    // Permite acesso a recursos de mídia e scripts locais
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true

                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
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

                            if (urlString.contains("filekeeper.net", ignoreCase = true)) {
                                currentDisplayUrl = urlString
                                return false
                            }

                            val cleanPath = urlString.substringBefore("?")
                            if (cleanPath.endsWith(".pkg", ignoreCase = true)) {
                                currentDisplayUrl = urlString
                                handleCapturedUrl(urlString)
                                return true
                            }

                            if (!isDomainPermitted(urlString)) {
                                return true
                            }

                            currentDisplayUrl = urlString
                            return false
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)

                            val isNotIntermediate = !url.contains("filekeeper.net", ignoreCase = true)
                            val isPkg = url.substringBefore("?").endsWith(".pkg", ignoreCase = true)

                            // Evita abortar requisições legítimas do Mocha
                            if (isNotIntermediate && !isPkg && !isDomainPermitted(url)) {
                                view.stopLoading()
                                if (currentPageUrl.isNotBlank() && view.url != currentPageUrl) {
                                    view.loadUrl(currentPageUrl)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            currentDisplayUrl = url
                            canGoBack = view.canGoBack()

                            val isPkg = url.substringBefore("?").endsWith(".pkg", ignoreCase = true)
                            if (!isPkg && isDomainPermitted(url)) {
                                currentPageUrl = url
                            }

                            if (!captured && !processing) {
                                status = "Página carregada. Clique no arquivo desejado para capturar."
                            }
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        AppLogger.log("[PkgLinkCaptureScreen] DownloadListener disparado: $url")
                        handleCapturedUrl(
                            url = url,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType
                        )
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
