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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var currentDisplayUrl by remember { mutableStateOf(sourceUrl) }
    var currentPageUrl by remember { mutableStateOf(sourceUrl) }
    var returnPageUrl by remember { mutableStateOf("") }
    var originPageUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Aguardando download do PKG...") }
    var captured by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
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

        val isSource = sourceHost.isNotEmpty() &&
                (host.contains(sourceHost) || sourceHost.contains(host))

        val isAllowed = allowedDomains.any { domain ->
            val cleanDomain = domain.trim().lowercase()
            cleanDomain.isNotBlank() && host.contains(cleanDomain)
        }

        val isCommonCdn = host.contains("filekeeper") ||
                host.contains("dlproxy") ||
                host.contains("akirabox") ||
                host.contains("mocha") ||
                host.contains("matchaup") ||
                host.contains("cloudflare") ||
                host.contains("hcaptcha")

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
            status = "Não foi possível salvar página de retorno."
            return
        }
        returnPageUrl = url
        status = "Página de retorno salva!"
        AppLogger.log("[PkgLinkCaptureScreen] Página de RETORNO salva: $returnPageUrl")
    }

    fun saveOriginPage() {
        val url = getCurrentBrowserUrl()
        if (url.isBlank()) {
            status = "Não foi possível salvar página de origem."
            return
        }
        originPageUrl = url
        status = "Página de origem salva!"
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
            status = "Aguardando download do PKG..."
            return
        }

        status = "Concluído! Retornando..."
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
        status = "Aguardando download do PKG..."
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
        status = "PKG capturado! Processando..."

        AppLogger.log("[PkgLinkCaptureScreen] Link capturado: $url")
        AppLogger.log("[PkgLinkCaptureScreen] Origem: $finalSourceUrl")

        val result = PkgCaptureResult(
            sourceUrl = finalSourceUrl,
            directUrl = url,
            fileName = finalFileName
        )

        onCaptured(result) {
            finishProcessingAndReturn()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    browser = this

                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = false

                    val defaultUa = settings.userAgentString
                    settings.userAgentString = if (defaultUa.isNotBlank()) {
                        defaultUa.replace("; wv", "").replace("Version/4.0 ", "")
                    } else {
                        BROWSER_USER_AGENT
                    }

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true

                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true

                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

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

                            view.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                                        window.chrome = window.chrome || { runtime: {} };
                                    } catch(e) {}
                                })();
                                """.trimIndent(), null
                            )

                            val isNotIntermediate = !url.contains("filekeeper.net", ignoreCase = true)
                            val isPkg = url.substringBefore("?").endsWith(".pkg", ignoreCase = true)

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

                            /*
                             * SCRIPT DE DESDOBRAMENTO (OPÇÃO 1):
                             * 1. Remove classes de loading/esqueleto que travam o container.
                             * 2. Localiza nós e elementos de arquivos que possam estar com display:none.
                             * 3. Registra um observador contínuo para renderizar os cards assim que a API responder.
                             */
                            view.evaluateJavascript(
                                """
                                (function() {
                                    function revealFiles() {
                                        try {
                                            // Remove esqueletos/barras de loading
                                            const skeletons = document.querySelectorAll('[class*="skeleton"], [class*="animate-pulse"], [class*="loading-bar"]');
                                            skeletons.forEach(el => {
                                                el.style.display = 'none';
                                            });

                                            // Localiza divs e links de arquivos e força a visibilidade
                                            const allElements = document.querySelectorAll('div, a, button');
                                            allElements.forEach(el => {
                                                const txt = (el.innerText || '').trim();
                                                if (txt.includes('.pkg') || txt.includes('41.52 GB') || txt.includes('1 MB')) {
                                                    el.style.display = 'block';
                                                    el.style.visibility = 'visible';
                                                    el.style.opacity = '1';
                                                    el.style.minHeight = '48px';
                                                    if (el.parentElement) {
                                                        el.parentElement.style.display = 'block';
                                                        el.parentElement.style.visibility = 'visible';
                                                        el.parentElement.style.opacity = '1';
                                                    }
                                                }
                                            });
                                            window.dispatchEvent(new Event('resize'));
                                        } catch(e) {}
                                    }

                                    revealFiles();

                                    // Executa repetidamente nos primeiros segundos enquanto o JS da página monta o DOM
                                    let attempts = 0;
                                    const interval = setInterval(function() {
                                        revealFiles();
                                        attempts++;
                                        if (attempts > 15) clearInterval(interval);
                                    }, 600);

                                    // Observa inserção dinâmica de novos nós no corpo da página
                                    const observer = new MutationObserver(function() {
                                        revealFiles();
                                    });
                                    if (document.body) {
                                        observer.observe(document.body, { childList: true, subtree: true });
                                    }
                                })();
                                """.trimIndent(), null
                            )

                            if (!captured && !processing) {
                                status = "Página carregada. Escolha o arquivo desejado."
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

        // Card superior com status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xDD121212)
            ),
            border = BorderStroke(1.dp, Color(0x66FFFFFF))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CAPTURA GTSTORE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            processing -> "PROCESSANDO..."
                            captured -> "✓ CAPTURADO"
                            returnPageUrl.isNotBlank() && originPageUrl.isNotBlank() -> "✓ ORIGEM & RETORNO"
                            returnPageUrl.isNotBlank() -> "1/2 RETORNO OK"
                            else -> "PRONTO"
                        },
                        color = when {
                            processing -> Color(0xFFFFC107)
                            captured || originPageUrl.isNotBlank() -> GreenLed
                            returnPageUrl.isNotBlank() -> Color(0xFF64B5F6)
                            else -> Color(0xFFAAAAAA)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (currentDisplayUrl.isNotBlank()) {
                    Text(
                        text = currentDisplayUrl,
                        color = Color(0xFF90CAF9),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Rodapé de botões flutuantes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    when {
                        returnPageUrl.isBlank() -> saveReturnPage()
                        originPageUrl.isBlank() -> saveOriginPage()
                    }
                },
                enabled = !processing && originPageUrl.isBlank(),
                modifier = Modifier
                    .weight(1.3f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        returnPageUrl.isBlank() -> Color(0xEE0070CC)
                        originPageUrl.isBlank() -> Color(0xEE35C759)
                        else -> Color(0xAA222222)
                    },
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = when {
                        returnPageUrl.isBlank() -> "1. SALVAR RETORNO"
                        originPageUrl.isBlank() -> "2. SALVAR ORIGEM"
                        else -> "PÁGINAS SALVAS"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    val view = browser
                    if (view != null && view.canGoBack()) {
                        view.goBack()
                    }
                },
                enabled = canGoBack && !processing,
                modifier = Modifier
                    .weight(0.85f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xDD2A2A2A),
                    contentColor = Color.White
                )
            ) {
                Text(text = "VOLTAR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onCancel,
                enabled = !processing,
                modifier = Modifier
                    .weight(0.85f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xDD2A2A2A),
                    contentColor = Color(0xFFFF6B6B)
                )
            ) {
                Text(text = "SAIR", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
