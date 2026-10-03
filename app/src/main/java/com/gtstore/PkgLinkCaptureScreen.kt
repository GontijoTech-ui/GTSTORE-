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
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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

    /*
     * Primeira etapa:
     * página para onde o WebView deverá voltar
     * depois que o processamento terminar.
     */
    var returnPageUrl by remember {
        mutableStateOf("")
    }

    /*
     * Segunda etapa:
     * página que será gravada no catálogo como
     * página de origem.
     */
    var originPageUrl by remember {
        mutableStateOf("")
    }

    var status by remember {
        mutableStateOf(
            "Aguardando início do download do PKG..."
        )
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
            Uri.parse(sourceUrl)
                .host
                ?.lowercase()
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /*
     * ========================================================
     * LIMPEZA DO WEBVIEW
     * ========================================================
     */

    DisposableEffect(Unit) {
        onDispose {
            browser?.stopLoading()
            browser?.destroy()
            browser = null
        }
    }

    /*
     * ========================================================
     * DOMÍNIOS PERMITIDOS
     * ========================================================
     */

    fun isDomainPermitted(url: String): Boolean {
        val host = try {
            Uri.parse(url)
                .host
                ?.lowercase()
                ?: ""
        } catch (_: Exception) {
            ""
        }

        if (host.isBlank()) {
            return true
        }

        val isSource =
            sourceHost.isNotEmpty() &&
                    (
                            host.contains(sourceHost) ||
                                    sourceHost.contains(host)
                            )

        val isAllowed =
            allowedDomains.any { domain ->
                val cleanDomain =
                    domain.trim().lowercase()

                cleanDomain.isNotBlank() &&
                        host.contains(cleanDomain)
            }

        /*
         * Domínios intermediários/CDN utilizados
         * pelo fluxo atual.
         */
        val isCommonCdn =
            host.contains("filekeeper") ||
                    host.contains("dlproxy") ||
                    host.contains("akirabox") ||
                    host.contains("mocha")

        return isSource ||
                isAllowed ||
                isCommonCdn
    }

    /*
     * ========================================================
     * URL ATUAL
     * ========================================================
     */

    fun getCurrentBrowserUrl(): String {
        val webViewUrl =
            browser?.url
                ?.trim()
                .orEmpty()

        return when {
            webViewUrl.isNotBlank() ->
                webViewUrl

            currentPageUrl.isNotBlank() ->
                currentPageUrl

            currentDisplayUrl.isNotBlank() ->
                currentDisplayUrl

            else ->
                sourceUrl
        }
    }

    /*
     * ========================================================
     * PRIMEIRO CLIQUE
     * ========================================================
     */

    fun saveReturnPage() {
        val url =
            getCurrentBrowserUrl()

        if (url.isBlank()) {
            status =
                "Não foi possível salvar a página de retorno."

            return
        }

        returnPageUrl = url

        status =
            "Página de retorno salva."

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Página de RETORNO salva: $returnPageUrl"
        )
    }

    /*
     * ========================================================
     * SEGUNDO CLIQUE
     * ========================================================
     */

    fun saveOriginPage() {
        val url =
            getCurrentBrowserUrl()

        if (url.isBlank()) {
            status =
                "Não foi possível salvar a página de origem."

            return
        }

        originPageUrl = url

        status =
            "Página de origem salva."

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Página de ORIGEM salva: $originPageUrl"
        )
    }

    /*
     * ========================================================
     * FINALIZAÇÃO DO PROCESSAMENTO
     * ========================================================
     */

    fun finishProcessingAndReturn() {
        if (!processing) {
            return
        }

        val targetUrl =
            returnPageUrl
                .trim()

        /*
         * Não existe página de retorno salva.
         */
        if (targetUrl.isBlank()) {
            processing = false
            captured = false

            /*
             * Reseta as duas etapas para permitir
             * uma nova captura.
             */
            returnPageUrl = ""
            originPageUrl = ""

            status =
                "Aguardando início do download do PKG..."

            AppLogger.log(
                "[PkgLinkCaptureScreen] " +
                        "Processamento concluído sem página de retorno. " +
                        "Estado da captura resetado."
            )

            return
        }

        status =
            "Processamento concluído. Voltando à página..."

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Navegando para página de retorno: $targetUrl"
        )

        val view =
            browser

        if (view != null) {
            view.post {
                view.loadUrl(targetUrl)
            }
        }

        /*
         * Libera uma nova captura.
         *
         * O WebView continua aberto.
         *
         * As páginas salvas são apagadas para que
         * o botão volte ao estado inicial.
         */
        processing = false
        captured = false

        returnPageUrl = ""
        originPageUrl = ""

        status =
            "Aguardando início do download do PKG..."
    }

    /*
     * ========================================================
     * CAPTURA DO LINK PKG
     * ========================================================
     */

    fun handleCapturedUrl(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null
    ) {
        /*
         * Evita capturas duplicadas enquanto o processamento
         * anterior ainda está em andamento.
         */
        if (captured || processing) {
            return
        }

        val isHttp =
            url.startsWith(
                "http://",
                ignoreCase = true
            )

        val isHttps =
            url.startsWith(
                "https://",
                ignoreCase = true
            )

        if (!isHttp && !isHttps) {
            return
        }

        val guessedFileName =
            URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType
            )

        val finalFileName =
            guessedFileName
                .trim()
                .ifBlank {
                    "download.pkg"
                }

        /*
         * A origem é obrigatoriamente a página salva
         * no segundo clique quando ela existir.
         *
         * O fallback mantém o comportamento anterior
         * caso o usuário não tenha salvo uma origem.
         */
        val finalSourceUrl =
            originPageUrl
                .trim()
                .ifBlank {
                    currentPageUrl
                        .trim()
                        .ifBlank {
                            sourceUrl
                        }
                }

        captured = true
        processing = true

        status =
            "Link PKG capturado. Processando..."

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Link capturado com sucesso: $url"
        )

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Página de origem utilizada: $finalSourceUrl"
        )

        AppLogger.log(
            "[PkgLinkCaptureScreen] " +
                    "Página de retorno: " +
                    returnPageUrl.ifBlank {
                        "(não definida)"
                    }
        )

        val result =
            PkgCaptureResult(
                sourceUrl = finalSourceUrl,
                directUrl = url,
                fileName = finalFileName
            )

        /*
         * O processamento real acontece no chamador.
         *
         * Quando terminar, deverá chamar onComplete().
         */
        onCaptured(
            result,
            {
                finishProcessingAndReturn()
            }
        )
    }

    /*
     * ========================================================
     * INTERFACE
     * ========================================================
     */

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {

        /*
         * ====================================================
         * STATUS
         * ====================================================
         */

        Card(
            modifier =
                Modifier.fillMaxWidth(),

            colors =
                CardDefaults.cardColors(
                    containerColor = CardBlack
                ),

            border =
                BorderStroke(
                    width = 1.dp,
                    color = BorderDark
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(12.dp),

                verticalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {

                Text(
                    text = "CAPTURA DE LINK",
                    color = TextWhite
                )

                Text(
                    text = status,
                    color =
                        when {
                            processing ->
                                Color(0xFFFFC107)

                            captured ->
                                GreenLed

                            else ->
                                TextMuted
                        }
                )

                if (currentDisplayUrl.isNotBlank()) {

                    Text(
                        text = currentDisplayUrl,
                        color = Color(0xFF64B5F6),
                        maxLines = 1
                    )
                }

                if (returnPageUrl.isNotBlank()) {

                    Text(
                        text = "✓ Página de retorno salva",
                        color = GreenLed,
                        maxLines = 1
                    )
                }

                if (originPageUrl.isNotBlank()) {

                    Text(
                        text = "✓ Página de origem salva",
                        color = GreenLed,
                        maxLines = 1
                    )
                }
            }
        }

        /*
         * ====================================================
         * BOTÃO DE DUAS ETAPAS
         * ====================================================
         */

        Button(
            onClick = {

                when {

                    returnPageUrl.isBlank() -> {
                        saveReturnPage()
                    }

                    originPageUrl.isBlank() -> {
                        saveOriginPage()
                    }
                }
            },

            enabled =
                !processing &&
                        originPageUrl.isBlank(),

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),

            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        Color(0xFF303030),

                    disabledContainerColor =
                        Color(0xFF181818),

                    contentColor =
                        Color.White,

                    disabledContentColor =
                        Color(0xFF666666)
                )
        ) {

            Text(
                text =
                    when {
                        returnPageUrl.isBlank() ->
                            "SALVAR PÁGINA DE RETORNO"

                        originPageUrl.isBlank() ->
                            "SALVAR PÁGINA DE ORIGEM"

                        else ->
                            "PÁGINAS SALVAS"
                    }
            )
        }

        /*
         * ====================================================
         * BOTÕES DE NAVEGAÇÃO
         * ====================================================
         */

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),

            horizontalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            Button(
                onClick = {

                    val view =
                        browser

                    if (
                        view != null &&
                        view.canGoBack()
                    ) {
                        view.goBack()
                    }
                },

                enabled =
                    canGoBack &&
                            !processing,

                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFF303030),

                        disabledContainerColor =
                            Color(0xFF181818),

                        contentColor =
                            Color.White,

                        disabledContentColor =
                            Color(0xFF666666)
                    )
            ) {

                Text(
                    text = "VOLTAR PÁGINA"
                )
            }

            Button(
                onClick = onCancel,

                enabled =
                    !processing,

                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Color(0xFF303030),

                        disabledContainerColor =
                            Color(0xFF181818),

                        contentColor =
                            Color.White,

                        disabledContentColor =
                            Color(0xFF666666)
                    )
            ) {

                Text(
                    text = "CANCELAR"
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(2.dp)
        )

        /*
         * ====================================================
         * WEBVIEW
         * ====================================================
         */

        AndroidView(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),

            factory = { context ->

                WebView(context).apply {

                    browser = this

                    /*
                     * ----------------------------------------
                     * CONFIGURAÇÕES
                     * ----------------------------------------
                     */

                    settings.userAgentString =
                        BROWSER_USER_AGENT

                    settings.javaScriptEnabled =
                        true

                    settings.domStorageEnabled =
                        true

                    settings.databaseEnabled =
                        true

                    settings.loadsImagesAutomatically =
                        true

                    settings.javaScriptCanOpenWindowsAutomatically =
                        true

                    settings.setSupportMultipleWindows(
                        false
                    )

                    settings.mixedContentMode =
                        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    /*
                     * ----------------------------------------
                     * COOKIES
                     * ----------------------------------------
                     */

                    val cookieManager =
                        CookieManager.getInstance()

                    cookieManager.setAcceptCookie(
                        true
                    )

                    cookieManager.setAcceptThirdPartyCookies(
                        this,
                        true
                    )

                    webChromeClient =
                        WebChromeClient()

                    /*
                     * ----------------------------------------
                     * WEBVIEW CLIENT
                     * ----------------------------------------
                     */

                    webViewClient =
                        object : WebViewClient() {

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {

                                val urlString =
                                    request.url.toString()

                                /*
                                 * Filekeeper não deve ser tratado
                                 * como binário direto.
                                 *
                                 * Ele precisa carregar normalmente
                                 * para gerar a sessão/redirecionamento.
                                 */
                                if (
                                    urlString.contains(
                                        "filekeeper.net",
                                        ignoreCase = true
                                    )
                                ) {

                                    currentDisplayUrl =
                                        urlString

                                    return false
                                }

                                /*
                                 * --------------------------------
                                 * CAPTURA DE .PKG
                                 * --------------------------------
                                 */

                                val cleanPath =
                                    urlString
                                        .substringBefore("?")

                                if (
                                    cleanPath.endsWith(
                                        ".pkg",
                                        ignoreCase = true
                                    )
                                ) {

                                    currentDisplayUrl =
                                        urlString

                                    handleCapturedUrl(
                                        urlString
                                    )

                                    /*
                                     * Não deixa o WebView
                                     * navegar para o binário.
                                     */
                                    return true
                                }

                                /*
                                 * --------------------------------
                                 * BLOQUEIO DE DOMÍNIOS
                                 * --------------------------------
                                 */

                                if (
                                    !isDomainPermitted(
                                        urlString
                                    )
                                ) {
                                    return true
                                }

                                currentDisplayUrl =
                                    urlString

                                return false
                            }

                            override fun onPageStarted(
                                view: WebView,
                                url: String,
                                favicon: android.graphics.Bitmap?
                            ) {

                                super.onPageStarted(
                                    view,
                                    url,
                                    favicon
                                )

                                val isNotIntermediate =
                                    !url.contains(
                                        "filekeeper.net",
                                        ignoreCase = true
                                    )

                                val isPkg =
                                    url
                                        .substringBefore("?")
                                        .endsWith(
                                            ".pkg",
                                            ignoreCase = true
                                        )

                                /*
                                 * Bloqueia navegação para domínios
                                 * não autorizados.
                                 */
                                if (
                                    isNotIntermediate &&
                                    !isPkg &&
                                    !isDomainPermitted(url)
                                ) {

                                    view.stopLoading()

                                    if (
                                        currentPageUrl.isNotBlank() &&
                                        view.url != currentPageUrl
                                    ) {

                                        view.loadUrl(
                                            currentPageUrl
                                        )
                                    }
                                }
                            }

                            override fun onPageFinished(
                                view: WebView,
                                url: String
                            ) {

                                super.onPageFinished(
                                    view,
                                    url
                                )

                                currentDisplayUrl =
                                    url

                                canGoBack =
                                    view.canGoBack()

                                val isPkg =
                                    url
                                        .substringBefore("?")
                                        .endsWith(
                                            ".pkg",
                                            ignoreCase = true
                                        )

                                /*
                                 * Atualiza somente a página
                                 * atualmente carregada.
                                 *
                                 * Isso NÃO altera:
                                 *
                                 * returnPageUrl
                                 * originPageUrl
                                 */
                                if (
                                    !isPkg &&
                                    isDomainPermitted(url)
                                ) {

                                    currentPageUrl =
                                        url
                                }

                                if (
                                    !captured &&
                                    !processing
                                ) {

                                    status =
                                        "Página carregada. " +
                                                "Clique para gerar ou iniciar o download."
                                }
                            }
                        }

                    /*
                     * ----------------------------------------
                     * DOWNLOAD LISTENER
                     * ----------------------------------------
                     */

                    setDownloadListener {
                            url,
                            userAgent,
                            contentDisposition,
                            mimeType,
                            contentLength ->

                        AppLogger.log(
                            "[PkgLinkCaptureScreen] " +
                                    "DownloadListener disparado: $url"
                        )

                        handleCapturedUrl(
                            url = url,
                            contentDisposition =
                                contentDisposition,
                            mimeType =
                                mimeType
                        )
                    }

                    /*
                     * ----------------------------------------
                     * PÁGINA INICIAL
                     * ----------------------------------------
                     */

                    loadUrl(sourceUrl)
                }
            },

            update = { view ->

                browser =
                    view

                canGoBack =
                    view.canGoBack()
            }
        )
    }
}
