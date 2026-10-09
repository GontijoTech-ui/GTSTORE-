package com.gtstore

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PkgCaptureResult(
    val sourceUrl: String,
    val directUrl: String,
    val fileName: String
)

private class MochaDiagnosticLogger(
    private val context: Context
) {
    fun start(sourceUrl: String, userAgent: String) {}
    fun log(message: String) {}
    fun finish() {}
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
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

    val capturedList = remember { mutableStateListOf<String>() }
    val coroutineScope = rememberCoroutineScope()

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

    fun isDownloadOrPkgUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.isBlank()) return false

        val cleanPath = lower.substringBefore("?")
        val isPkgExtension = cleanPath.endsWith(".pkg") || lower.contains(".pkg?")
        val isAkiraDownloadNode = lower.contains("download.akirabox") ||
                (lower.contains("akirabox") && (lower.contains("/dl/") || lower.contains("/get/") || lower.contains("/download")))
        val isVikingFileNode = lower.contains("vikingfile.com/d/")

        return isPkgExtension || isAkiraDownloadNode || isVikingFileNode
    }

    fun isDomainPermitted(url: String): Boolean {
        if (isDownloadOrPkgUrl(url)) return true

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
                host.contains("vikingfile") ||
                host.contains("mocha") ||
                host.contains("matchaup") ||
                host.contains("cloudflare") ||
                host.contains("challenges.cloudflare") ||
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

    suspend fun resolveFinalUrl(originalUrl: String, defaultUa: String): String = withContext(Dispatchers.IO) {
        var currentUrl = originalUrl
        var redirects = 0

        try {
            while (redirects < 5) {
                val urlObj = URL(currentUrl)
                val connection = urlObj.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                
                connection.setRequestProperty("User-Agent", defaultUa)
                val cookies = CookieManager.getInstance().getCookie(currentUrl)
                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == 307 || 
                    responseCode == 308) {
                    
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("/")) {
                            "${urlObj.protocol}://${urlObj.host}${location}"
                        } else {
                            location
                        }
                        redirects++
                        continue
                    }
                }
                
                connection.disconnect()
                break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext currentUrl
    }

    fun handleCapturedUrl(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null,
        userAgent: String = browser?.settings?.userAgentString ?: ""
    ) {
        val isHttp = url.startsWith("http://", ignoreCase = true)
        val isHttps = url.startsWith("https://", ignoreCase = true)
        if (!isHttp && !isHttps) return

        if (capturedList.contains(url)) return
        
        status = "Resolvendo link final da CDN..."
        
        coroutineScope.launch {
            val finalUrl = resolveFinalUrl(url, userAgent)
            
            withContext(Dispatchers.Main) {
                if (capturedList.contains(finalUrl)) return@withContext
                capturedList.add(finalUrl)

                val guessedFileName = URLUtil.guessFileName(finalUrl, contentDisposition, mimeType)
                val finalFileName = guessedFileName.trim().ifBlank { "download.pkg" }

                val finalSourceUrl = originPageUrl.trim().ifBlank {
                    currentPageUrl.trim().ifBlank { sourceUrl }
                }

                captured = true
                status = "Capturado (${capturedList.size}): $finalFileName"

                AppLogger.log("[PkgLinkCaptureScreen] Link base: $url")
                AppLogger.log("[PkgLinkCaptureScreen] Link resolvido (CDN): $finalUrl")

                val result = PkgCaptureResult(
                    sourceUrl = finalSourceUrl,
                    directUrl = finalUrl,
                    fileName = finalFileName
                )

                onCaptured(result) {}

                browser?.removeCallbacks(null)
                browser?.postDelayed({
                    if (!processing && capturedList.isNotEmpty()) {
                        processing = true
                        status = "Todos os ${capturedList.size} ficheiros foram registados!"
                        finishProcessingAndReturn()
                    }
                }, 3000)
            }
        }
    }

    class BridgeInterface(private val onUrlFound: (String, String?) -> Unit) {
        @JavascriptInterface
        fun onCapturedUrl(url: String, fileName: String?) {
            if (url.isNotBlank()) {
                onUrlFound(url, fileName)
            }
        }
    }

    fun injectCaptureScript(view: WebView) {
        // Bloco JS modificado para nao conter nenhuma aspa dupla (") para evitar bugs do Kotlin AST
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    window.chrome = window.chrome || { runtime: {} };

                    function isTargetDownload(u) {
                        if (!u) return false;
                        const s = u.toLowerCase();
                        return s.includes('.pkg') || 
                               s.includes('download.akirabox') || 
                               (s.includes('akirabox') && (s.includes('/dl/') || s.includes('/get/'))) ||
                               s.includes('vikingfile.com/d/');
                    }

                    window.open = function(url) {
                        if (url && isTargetDownload(url)) {
                            try { window.GTStoreCapture.onCapturedUrl(url, ''); } catch(e) {}
                        }
                        return null; 
                    };

                    const sanitizeTargets = () => {
                        document.querySelectorAll('a[target]').forEach(a => a.removeAttribute('target'));
                    };
                    sanitizeTargets();

                    if (!window._gtFetchPatched) {
                        window._gtFetchPatched = true;
                        const origFetch = window.fetch;
                        window.fetch = async function(...args) {
                            const response = await origFetch.apply(this, args);
                            try {
                                const clone = response.clone();
                                clone.text().then(text => {
                                    const match = text.match(/https?:\/\/\S+\.pkg\S*/i) ||
                                                  text.match(/https?:\/\/\S*(?:download\.akirabox|akirabox\.(?:com|to|xyz)\/dl|vikingfile\.com\/d\/)\S*/i);
                                    if (match && match[0]) {
                                        window.GTStoreCapture.onCapturedUrl(match[0], '');
                                    }
                                }).catch(() => {});
                            } catch(e) {}
                            return response;
                        };
                    }

                    if (!window._gtXhrPatched) {
                        window._gtXhrPatched = true;
                        const origOpen = XMLHttpRequest.prototype.open;
                        const origSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function(m, u) {
                            this._url = u;
                            return origOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function() {
                            this.addEventListener('load', function() {
                                try {
                                    const text = this.responseText;
                                    const match = text.match(/https?:\/\/\S+\.pkg\S*/i) ||
                                                  text.match(/https?:\/\/\S*(?:download\.akirabox|akirabox\.(?:com|to|xyz)\/dl|vikingfile\.com\/d\/)\S*/i);
                                    if (match && match[0]) {
                                        window.GTStoreCapture.onCapturedUrl(match[0], '');
                                    }
                                } catch(e) {}
                            });
                            return origSend.apply(this, arguments);
                        };
                    }

                    document.addEventListener('click', function(e) {
                        const a = e.target.closest('a');
                        if (a && a.href && isTargetDownload(a.href)) {
                            try { window.GTStoreCapture.onCapturedUrl(a.href, a.getAttribute('download') || ''); } catch(err) {}
                        }
                    }, true);
                } catch(e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val diagnosticLogger = MochaDiagnosticLogger(context)

                WebView(context).apply {
                    browser = this

                    val defaultUa = settings.userAgentString

                    diagnosticLogger.start(
                        sourceUrl = sourceUrl,
                        userAgent = defaultUa
                    )

                    diagnosticLogger.log("[WEBVIEW] Criando WebView")
                    diagnosticLogger.log("[WEBVIEW] User-Agent: $defaultUa")

                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = false

                    settings.userAgentString = defaultUa
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")

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
                    settings.setSupportMultipleWindows(true)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    addJavascriptInterface(
                        BridgeInterface { capturedUrl, disposition ->
                            post {
                                handleCapturedUrl(
                                    url = capturedUrl, 
                                    contentDisposition = disposition,
                                    userAgent = defaultUa
                                )
                            }
                        },
                        "GTStoreCapture"
                    )

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            val tempWebView = WebView(view?.context ?: return false)
                            tempWebView.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    wView: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val popupUrl = request.url.toString()
                                    diagnosticLogger.log("[POPUP] $popupUrl")

                                    if (isDownloadOrPkgUrl(popupUrl)) {
                                        handleCapturedUrl(url = popupUrl, userAgent = defaultUa)
                                    } else if (isDomainPermitted(popupUrl)) {
                                        view?.loadUrl(popupUrl)
                                    } else {
                                        diagnosticLogger.log("[POPUP_BLOCKED] Propaganda bloqueada: $popupUrl")
                                    }
                                    return true
                                }
                            }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = tempWebView
                            resultMsg?.sendToTarget()
                            return true
                        }

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                            val message = consoleMessage.message()
                            val source = consoleMessage.sourceId()
                            val line = consoleMessage.lineNumber()
                            val level = consoleMessage.messageLevel().toString()

                            diagnosticLogger.log("[JS][$level] $message | $source:$line")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            try {
                                val requestUrl = request.url.toString()
                                diagnosticLogger.log("[REQ] ${request.method} $requestUrl | mainFrame=${request.isForMainFrame}")

                                if (isDownloadOrPkgUrl(requestUrl)) {
                                    view.post {
                                        handleCapturedUrl(url = requestUrl, userAgent = defaultUa)
                                    }
                                }
                            } catch (_: Exception) {}

                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val urlString = request.url.toString()
                            diagnosticLogger.log("[NAV] $urlString")

                            if (urlString.contains("filekeeper.net", ignoreCase = true)) {
                                currentDisplayUrl = urlString
                                return false
                            }

                            if (isDownloadOrPkgUrl(urlString)) {
                                diagnosticLogger.log("[PKG] URL de download detectada: $urlString")
                                currentDisplayUrl = urlString
                                handleCapturedUrl(url = urlString, userAgent = defaultUa)
                                return true
                            }

                            if (!isDomainPermitted(urlString)) {
                                diagnosticLogger.log("[NAV_BLOCK] Navegação bloqueada: $urlString")
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
                            diagnosticLogger.log("[PAGE_START] $url")

                            injectCaptureScript(view)

                            if (isDownloadOrPkgUrl(url)) {
                                handleCapturedUrl(url = url, userAgent = defaultUa)
                                view.stopLoading()
                                return
                            }

                            val isNotIntermediate = !url.contains("filekeeper.net", ignoreCase = true)
                            if (isNotIntermediate && !isDomainPermitted(url)) {
                                diagnosticLogger.log("[PAGE_BLOCK] Página não permitida: $url")
                                view.stopLoading()

                                if (currentPageUrl.isNotBlank() && view.url != currentPageUrl) {
                                    diagnosticLogger.log("[PAGE_RETURN] Voltando para: $currentPageUrl")
                                    view.loadUrl(currentPageUrl)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            diagnosticLogger.log("[PAGE_FINISHED] $url")

                            currentDisplayUrl = url
                            canGoBack = view.canGoBack()

                            if (!isDownloadOrPkgUrl(url) && isDomainPermitted(url)) {
                                currentPageUrl = url
                            }

                            injectCaptureScript(view)

                            view.evaluateJavascript(
                                """
                                (function() {
                                    try {
                                        window.dispatchEvent(new Event('resize'));
                                        window.dispatchEvent(new Event('scroll'));
                                    } catch(e) {}
                                })();
                                """.trimIndent(),
                                null
                            )

                            if (!captured && !processing) {
                                status = "Página carregada."
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            diagnosticLogger.log("[ERROR] ${request.url} | code=${error.errorCode} | description=${error.description}")
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            diagnosticLogger.log("[HTTP] ${request.url} | status=${errorResponse.statusCode} | reason=${errorResponse.reasonPhrase}")
                        }
                    }

                    setDownloadListener { url, userAgentLocal, contentDisposition, mimeType, _ ->
                        diagnosticLogger.log("[DOWNLOAD] URL=$url")
                        diagnosticLogger.log("[DOWNLOAD] MIME=$mimeType")
                        diagnosticLogger.log("[DOWNLOAD] Content-Disposition=$contentDisposition")

                        AppLogger.log("[PkgLinkCaptureScreen] DownloadListener disparado: $url")

                        handleCapturedUrl(
                            url = url,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                            userAgent = defaultUa
                        )
                    }

                    diagnosticLogger.log("[LOAD] Carregando URL inicial: $sourceUrl")
                    loadUrl(sourceUrl)
                }
            },
            update = { view ->
                browser = view
                canGoBack = view.canGoBack()
            }
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xDD121212)),
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
                            captured -> "[OK] CAPTURADO (${capturedList.size})"
                            returnPageUrl.isNotBlank() && originPageUrl.isNotBlank() -> "[OK] ORIGEM & RETORNO"
                            returnPageUrl.isNotBlank() -> "1/2 RETORNO OK"
                            status.contains("Resolvendo") -> "RESOLVENDO ROTA..."
                            else -> "PRONTO"
                        },
                        color = when {
                            processing || status.contains("Resolvendo") -> Color(0xFFFFC107)
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
