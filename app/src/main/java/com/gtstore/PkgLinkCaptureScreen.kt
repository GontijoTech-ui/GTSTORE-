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

    // Identifica se a URL é o download real, endpoint do AkiraBox ou redirecionador do Vikingfile
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

    // Função que segue redirecionamentos 302 em background para achar o nó CDN (ex: Vikingfile)
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
                            "${urlObj.protocol}://${urlObj.host}$location"
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
            // Segue redirects em background
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
                status = "Capturado (${capturedList.size}):$finalFileName"

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
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    window.chrome = window.chrome || { runtime: {} };

                    function isTargetDownload(u) {
                        if (!u) return false;
                        const s = u.toLowerCase();
                        return s.includes('.pkg') || 
                               s.includes('download.
