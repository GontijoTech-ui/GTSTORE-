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

    // 1º Toque: URL da página para onde o WebView deve retornar
    var returnTargetUrl by remember { mutableStateOf<String?>(null) }
    // 2º Toque: URL da página onde o download é acionado (para updates)
    var originUpdateUrl by remember { mutableStateOf<String?>(null) }

    var status by remember { mutableStateOf("1º Toque: Marque a página de RETORNO quando estiver nela.") }
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

        // A origem será a definida no 2º toque (ou fallback da página atual)
        val pageWhereDownloadTriggered = originUpdateUrl 
            ?: view?.url 
            ?: currentDisplayUrl

        status = "PKG capturado! Gravando e retornando à página salva..."
        AppLogger.log("[PkgLinkCaptureScreen] Origem para atualizações: $pageWhereDownloadTriggered")
        AppLogger.log("[PkgLinkCaptureScreen] Link direto capturado: $url")

        // 1. Notifica o CatalogManager para processar o PKG em background
        onCaptured(
            PkgCaptureResult(
                sourceUrl = pageWhereDownloadTriggered,
                directUrl = url,
                fileName = finalFileName
            )
        )

        // 2. Não fecha o WebView: apenas carrega a página de retorno cadastrada
        view?.postDelayed({
            val target = returnTargetUrl
            if (!target.isNullOrBlank()) {
                AppLogger.log("[PkgLinkCaptureScreen] Retornando para página cadastrada: $target")
                view.loadUrl(target)
            } else if (view.canGoBack()) {
                view.goBack()
            } else {
                view.loadUrl(sourceUrl)
            }

            // Reseta apenas a origem do arquivo para o próximo download, mantendo o retorno se quiser
            originUpdateUrl = null
            status = "Pronto para o próximo jogo! Origem resetada."
            processingCapture = false
        }, 1200)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Painel Superior de Status e Indicadores
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBlack),
            border = BorderStroke(width = 1.dp, color = BorderDark)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "CAPTURA CONTÍNUA", color = TextWhite, fontWeight = FontWeight.Bold)
                    Text(
                        text = when {
                            originUpdateUrl != null -> "PASSO 2/2: PRONTO P/ DOWNLOAD"
                            returnTargetUrl != null -> "PASSO 1/2: RETORNO FIXADO"
                            else -> "AGUARDANDO CONFIGURAÇÃO"
                        },
                        color = if (originUpdateUrl != null) GreenLed else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = status, 
                    color = if (processingCapture) GreenLed else TextMuted,
                    fontSize = 13.sp
                )

                if (returnTargetUrl != null) {
                    Text(
                        text = "Voltar para: ${returnTargetUrl!!}",
                        color = Color(0xFFFFB300),
                        maxLines = 1,
                        fontSize = 11.sp
                    )
                }
                if (originUpdateUrl != null) {
                    Text(
                        text = "Origem update: ${originUpdateUrl!!}",
                        color = GreenLed,
                        maxLines = 1,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Barra de Ações: Botão Duplo Inteligente, Voltar e Concluir
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Botão com comportamento de 2 fases
            Button(
                onClick = {
                    val current = browser?.url?.trim() ?: currentDisplayUrl.trim()
                    if (current.isNotBlank()) {
                        if (returnTargetUrl == null) {
                            // 1º Toque: Cadastra o ponto de retorno
                            returnTargetUrl = current
                            status = "Retorno cadastrado! Agora navegue até o download e dê o 2º toque."
                            AppLogger.log("[PkgLinkCaptureScreen] Retorno definido: $current")
                        } else if (originUpdateUrl == null) {
                            // 2º Toque: Cadastra a página de origem da atualização
                            originUpdateUrl = current
                            status = "Origem salva! Pode clicar no botão de download do PKG."
                            AppLogger.log("[PkgLinkCaptureScreen] Origem definida: $current")
                        } else {
                            // Toque extra: permite atualizar a origem se mudou de ideia
                            originUpdateUrl = current
                            status = "Origem atualizada! Clique no botão de download do PKG."
                        }
                    }
                },
                modifier = Modifier
                    .weight(1.4f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        originUpdateUrl != null -> Color(0xFF1B5E20) // Verde
                        returnTargetUrl != null -> Color(0xFFE65100) // Laranja
                        else -> Color(0xFF1565C0) // Azul
                    },
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = when {
                        originUpdateUrl != null -> "2º ORIGEM SALVA ✔"
                        returnTargetUrl != null -> "2º SALVAR ORIGEM"
                        else -> "1º DEFINIR RETORNO"
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
                enabled = canGoBack,
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF303030),
                    disabledContainerColor = Color(0xFF181818),
                    contentColor = Color.White,
                    disabledContentColor = Color(0xFF666666)
                )
            ) {
                Text(text = "VOLTAR", fontSize = 11.sp)
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxSize(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF424242),
                    contentColor = Color.White
                )
            ) {
                Text(text = "CONCLUIR", fontSize = 11.sp)
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
                                if (returnTargetUrl == null) {
                                    status = "1º Toque: Clique em '1º DEFINIR RETORNO' na página base."
                                } else if (originUpdateUrl == null) {
                                    status = "Navegue ao download e clique em '2º SALVAR ORIGEM'."
                                }
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
