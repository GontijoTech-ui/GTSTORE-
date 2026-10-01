package com.gtstore

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
    onCaptured: (PkgCaptureResult) -> Unit,
    onCancel: () -> Unit
) {
    var currentUrl by remember {
        mutableStateOf(sourceUrl)
    }

    var status by remember {
        mutableStateOf(
            "Navegue normalmente pelo site. " +
                    "Resolva o CAPTCHA manualmente e inicie o download do PKG."
        )
    }

    var captured by remember {
        mutableStateOf(false)
    }

    var canGoBack by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        /*
         * BOTÕES DO WEBVIEW
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            /*
             * VOLTAR PÁGINA
             *
             * A referência ao WebView será mantida através
             * de uma variável local.
             */
            var webView by remember {
                mutableStateOf<WebView?>(null)
            }

            Button(
                onClick = {
                    webView?.let { view ->
                        if (view.canGoBack()) {
                            view.goBack()
                        }
                    }
                },
                enabled = canGoBack,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222),
                    disabledContainerColor = Color(0xFF111111),
                    disabledContentColor = Color(0xFF555555)
                )
            ) {
                Text("VOLTAR PÁGINA")
            }

            /*
             * CANCELAR
             *
             * Sai completamente do navegador e volta
             * para o Catalog Manager.
             */
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
         * INFORMAÇÕES
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
                    text = "CAPTURA DE LINK",
                    color = TextWhite
                )

                Text(
                    text = status,
                    color = if (captured) {
                        GreenLed
                    } else {
                        TextMuted
                    }
                )

                if (currentUrl.isNotBlank()) {
                    Text(
                        text = currentUrl,
                        color = Color(0xFF64B5F6),
                        maxLines = 2
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

                    /*
                     * Guardamos a referência para o botão
                     * VOLTAR PÁGINA.
                     */
                    webView = this

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)

                    /*
                     * Mantém o User-Agent padrão do WebView.
                     */
                    settings.userAgentString =
                        settings.userAgentString

                    /*
                     * Cookies são importantes para manter
                     * a sessão do site durante a navegação.
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

                    webChromeClient = WebChromeClient()

                    /*
                     * CONTROLE DE NAVEGAÇÃO
                     */
                    webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {

                            currentUrl =
                                request.url.toString()

                            return false
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {

                            currentUrl = url

                            /*
                             * Atualiza o estado do botão
                             * VOLTAR PÁGINA.
                             */
                            canGoBack = view.canGoBack()

                            if (!captured) {

                                status =
                                    "Página carregada. " +
                                            "Continue a navegação até " +
                                            "iniciar o download do PKG."
                            }
                        }
                    }

                    /*
                     * CAPTURA DO DOWNLOAD
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
                             * Só aceitamos links HTTP/HTTPS.
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
                                    "Download detectado, " +
                                            "mas o endereço não é HTTP/HTTPS."

                                return@DownloadListener
                            }

                            /*
                             * Tenta descobrir o nome do arquivo.
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
                             * Marcamos como capturado.
                             */
                            captured = true

                            status =
                                "Link PKG capturado. " +
                                        "Validando arquivo..."

                            /*
                             * Entrega o resultado ao Catalog Manager.
                             */
                            onCaptured(
                                PkgCaptureResult(
                                    sourceUrl = sourceUrl,
                                    directUrl = url,
                                    fileName = finalFileName
                                )
                            )
                        }
                    }

                    /*
                     * Abre a URL inicial.
                     */
                    loadUrl(sourceUrl)
                }
            },

            /*
             * Atualiza a referência caso o Android recrie
             * o WebView.
             */
            update = { view ->
                webView = view
                canGoBack = view.canGoBack()
            }
        )
    }
            }
