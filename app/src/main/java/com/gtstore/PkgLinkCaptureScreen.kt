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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkgLinkCaptureScreen(
    sourceUrl: String,
    allowedDomains: List<String> = emptyList(),
    onCaptured: (PkgCaptureResult) -> Unit,
    onCancel: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(sourceUrl) }
    var status by remember { mutableStateOf("Aguardando início do download do PKG...") }
    var captured by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<WebView?>(null) }

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
        if (host.isBlank()) return true

        val isSource = sourceHost.isNotEmpty() && (host.contains(sourceHost) || sourceHost.contains(host))
        val isAllowed = allowedDomains.any { domain ->
            domain.isNotBlank() && host.contains(domain)
        }
        return isSource || isAllowed
    }

    fun handleCapturedUrl(url: String, contentDisposition: String? = null, mimeType: String? = null) {
        if (captured) return

        val isHttp = url.startsWith("http://", ignoreCase = true)
        val isHttps = url.startsWith("https://", ignoreCase = true)

        if (!isHttp && !isHttps) {
            return
        }

        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val finalFileName = guessedFileName.trim().ifBlank { "download.pkg" }

        captured = true
        status = "Link PKG capturado. Validando arquivo..."

        onCaptured(
            PkgCaptureResult(
                sourceUrl = sourceUrl,
                directUrl = url,
                fileName = finalFileName
            )
        )
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
                Text(
                    text = "CAPTURA DE LINK",
                    color = TextWhite
                )

                Text(
                    text = status,
                    color = if (captured) GreenLed else TextMuted
                )

                if (currentUrl.isNotBlank()) {
                    Text(
                        text = currentUrl,
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

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
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

                            if (urlString.substringBefore("?").endsWith(".pkg", ignoreCase = true)) {
                                currentUrl = urlString
                                handleCapturedUrl(urlString)
                                return true
                            }

                            // Volta imediatamente em silêncio
                            if (!isDomainPermitted(urlString)) {
                                if (view.canGoBack()) {
                                    view.goBack()
                                }
                                return true
                            }

                            currentUrl = urlString
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)

                            // Interrompe carregamentos indesejados via script e volta sem alterar status
                            if (!url.substringBefore("?").endsWith(".pkg", ignoreCase = true) && !isDomainPermitted(url)) {
                                view.stopLoading()
                                if (view.canGoBack()) {
                                    view.goBack()
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            canGoBack = view.canGoBack()

                            if (!captured) {
                                status = "Página carregada. Continue a navegação até iniciar o download do PKG."
                            }
                        }
                    }

                    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
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
