package com.gtstore

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLDecoder

data class PkgCaptureResult(
    val sourceUrl: String,
    val directUrl: String,
    val fileName: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PkgLinkCaptureScreen(
    sourceUrl: String,
    onCaptured: (PkgCaptureResult) -> Unit,
    onCancel: () -> Unit
) {
    var currentUrl by remember {
        mutableStateOf(sourceUrl)
    }

    var status by remember {
        mutableStateOf(
            "Navegue normalmente pelo site. Resolva o CAPTCHA manualmente e inicie o download do PKG."
        )
    }

    var captured by remember {
        mutableStateOf(false)
    }

    /*
     * Mantemos uma referência ao WebView para que o botão VOLTAR
     * possa controlar o histórico da navegação.
     */
    var webViewReference by remember {
        mutableStateOf<WebView?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        /*
         * BARRA DE CONTROLE
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Button(
                onClick = {
                    val webView = webViewReference

                    if (webView != null && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        onCancel()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222)
                )
            ) {
                Text("VOLTAR")
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222)
                )
            ) {
                Text("CANCELAR")
            }
        }

        /*
         * STATUS DA CAPTURA
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = CardBlack
            ),
            border = BorderStroke(
                1.dp,
                BorderDark
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

                Text(
                    "CAPTURA DE LINK",
                    color = TextWhite
                )

                Text(
                    status,
                    color = if (captured) {
                        GreenLed
                    } else {
                        TextMuted
                    }
                )

                if (currentUrl.isNotBlank()) {
                    Text(
                        currentUrl,
                        color = Color(0xFF64B5F6),
                        maxLines = 3
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        /*
         * WEBVIEW
         */
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),

            factory = { context ->

                WebView(context).apply {

                    webViewReference = this

                    /*
                     * CONFIGURAÇÕES DO WEBVIEW
                     */
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)

                    /*
                     * Mantém o User-Agent normal do WebView.
                     */
                    settings.userAgentString =
                        settings.userAgentString

                    /*
                     * COOKIES
                     *
                     * Importante para sites que utilizam sessão,
                     * CAPTCHA ou autenticação durante a navegação.
                     */
                    CookieManager
                        .getInstance()
                        .setAcceptCookie(true)

                    CookieManager
                        .getInstance()
                        .setAcceptThirdPartyCookies(
                            this,
                            true
                        )

                    /*
                     * Permite recursos adicionais do site.
                     */
                    webChromeClient = WebChromeClient()

                    /*
                     * CONTROLE DE NAVEGAÇÃO
                     */
                    webViewClient = object : WebViewClient() {

                        /*
                         * Intercepta URLs antes que o WebView tente
                         * carregá-las.
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {

                            val url =
                                request.url.toString()

                            currentUrl = url

                            /*
                             * =================================================
                             * TRATAMENTO DO SCHEME "shopeebr://"
                             * =================================================
                             *
                             * Alguns sites da Shopee não apontam diretamente
                             * para HTTPS.
                             *
                             * Eles fazem algo como:
                             *
                             * shopeebr://reactPath?
                             * navigate_url=https%3A%2F%2Fshopee.com.br...
                             *
                             * O WebView não conhece "shopeebr://", causando:
                             *
                             * net::ERR_UNKNOWN_URL_SCHEME
                             *
                             * Aqui extraímos "navigate_url", decodificamos
                             * e carregamos a URL HTTPS no próprio WebView.
                             */
                            if (
                                url.startsWith(
                                    "shopeebr://",
                                    ignoreCase = true
                                )
                            ) {

                                try {

                                    val uri =
                                        Uri.parse(url)

                                    val navigateUrl =
                                        uri.getQueryParameter(
                                            "navigate_url"
                                        )

                                    if (
                                        !navigateUrl.isNullOrBlank()
                                    ) {

                                        /*
                                         * O Android normalmente já entrega
                                         * o parâmetro decodificado através
                                         * de getQueryParameter().
                                         *
                                         * Caso ainda esteja codificado,
                                         * fazemos uma segunda tentativa.
                                         */
                                        val decodedUrl =
                                            try {
                                                URLDecoder.decode(
                                                    navigateUrl,
                                                    "UTF-8"
                                                )
                                            } catch (
                                                _: Exception
                                            ) {
                                                navigateUrl
                                            }

                                        if (
                                            decodedUrl.startsWith(
                                                "http://",
                                                ignoreCase = true
                                            ) ||
                                            decodedUrl.startsWith(
                                                "https://",
                                                ignoreCase = true
                                            )
                                        ) {

                                            currentUrl =
                                                decodedUrl

                                            status =
                                                "Continuando a navegação..."

                                            view.loadUrl(
                                                decodedUrl
                                            )

                                            return true
                                        }
                                    }

                                } catch (
                                    _: Exception
                                ) {
                                    /*
                                     * Se não for possível interpretar
                                     * o endereço, simplesmente impedimos
                                     * que o WebView mostre
                                     * ERR_UNKNOWN_URL_SCHEME.
                                     */
                                }

                                status =
                                    "Redirecionamento do site não pôde ser processado."

                                return true
                            }

                            /*
                             * Outros schemes que não sejam HTTP/HTTPS
                             * também não devem gerar uma página de erro
                             * dentro do WebView.
                             */
                            if (
                                !url.startsWith(
                                    "http://",
                                    ignoreCase = true
                                ) &&
                                !url.startsWith(
                                    "https://",
                                    ignoreCase = true
                                )
                            ) {

                                status =
                                    "O site tentou abrir um endereço externo."

                                return true
                            }

                            /*
                             * HTTP/HTTPS normal.
                             */
                            return false
                        }

                        /*
                         * Compatibilidade com navegação iniciada por
                         * métodos antigos do WebView.
                         */
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            url: String
                        ): Boolean {

                            currentUrl = url

                            /*
                             * Tratamento do shopeebr:// também nesta
                             * sobrecarga.
                             */
                            if (
                                url.startsWith(
                                    "shopeebr://",
                                    ignoreCase = true
                                )
                            ) {

                                try {

                                    val uri =
                                        Uri.parse(url)

                                    val navigateUrl =
                                        uri.getQueryParameter(
                                            "navigate_url"
                                        )

                                    if (
                                        !navigateUrl.isNullOrBlank()
                                    ) {

                                        val decodedUrl =
                                            try {
                                                URLDecoder.decode(
                                                    navigateUrl,
                                                    "UTF-8"
                                                )
                                            } catch (
                                                _: Exception
                                            ) {
                                                navigateUrl
                                            }

                                        if (
                                            decodedUrl.startsWith(
                                                "http://",
                                                ignoreCase = true
                                            ) ||
                                            decodedUrl.startsWith(
                                                "https://",
                                                ignoreCase = true
                                            )
                                        ) {

                                            currentUrl =
                                                decodedUrl

                                            status =
                                                "Continuando a navegação..."

                                            view.loadUrl(
                                                decodedUrl
                                            )

                                            return true
                                        }
                                    }

                                } catch (
                                    _: Exception
                                ) {
                                }

                                status =
                                    "Redirecionamento do site não pôde ser processado."

                                return true
                            }

                            if (
                                !url.startsWith(
                                    "http://",
                                    ignoreCase = true
                                ) &&
                                !url.startsWith(
                                    "https://",
                                    ignoreCase = true
                                )
                            ) {

                                status =
                                    "O site tentou abrir um endereço externo."

                                return true
                            }

                            return false
                        }

                        /*
                         * Página terminou de carregar.
                         */
                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {

                            currentUrl = url

                            if (!captured) {

                                status =
                                    "Página carregada. Continue a navegação até iniciar o download do PKG."
                            }
                        }
                    }

                    /*
                     * =========================================================
                     * CAPTURA DO DOWNLOAD
                     * =========================================================
                     *
                     * Este callback somente é chamado quando o WebView
                     * identifica uma tentativa de download.
                     *
                     * O shopeebr:// NÃO chega aqui como PKG.
                     */
                    setDownloadListener(
                        DownloadListener {
                                url,
                                userAgent,
                                contentDisposition,
                                mimeType,
                                contentLength ->

                            if (captured) {
                                return@DownloadListener
                            }

                            /*
                             * Precisamos de uma URL HTTP/HTTPS real.
                             */
                            if (
                                !url.startsWith(
                                    "http://",
                                    ignoreCase = true
                                ) &&
                                !url.startsWith(
                                    "https://",
                                    ignoreCase = true
                                )
                            ) {

                                status =
                                    "Download detectado, mas o endereço não é HTTP/HTTPS."

                                return@DownloadListener
                            }

                            /*
                             * Tenta descobrir o nome real do arquivo.
                             */
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
                             * Verificação adicional.
                             *
                             * Se o servidor informar um nome que não
                             * pareça PKG, ainda mantemos o link porque
                             * alguns servidores utilizam nomes genéricos.
                             */
                            val normalizedFileName =
                                finalFileName
                                    .substringBefore("?")
                                    .substringBefore("#")
                                    .trim()

                            captured = true

                            status =
                                "Link de download capturado. Validando arquivo..."

                            /*
                             * Entrega o link para o CatalogManager.
                             *
                             * sourceUrl:
                             * URL original digitada pelo usuário.
                             *
                             * directUrl:
                             * URL real fornecida pelo site no download.
                             *
                             * fileName:
                             * nome descoberto pelo WebView.
                             */
                            onCaptured(
                                PkgCaptureResult(
                                    sourceUrl = sourceUrl,
                                    directUrl = url,
                                    fileName =
                                        normalizedFileName
                                )
                            )
                        }
                    )

                    /*
                     * PRIMEIRO CARREGAMENTO
                     */
                    loadUrl(sourceUrl)
                }
            },

            /*
             * Mantém a referência atualizada caso o Compose
             * recrie a View.
             */
            update = { webView ->

                webViewReference = webView
            }
        )
    }
}
