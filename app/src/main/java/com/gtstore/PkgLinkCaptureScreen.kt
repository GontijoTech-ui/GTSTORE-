package com.gtstore

import android.annotation.SuppressLint
import android.webkit.CookieManager
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

    var browser by remember {
        mutableStateOf<WebView?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ---------------------------------------------------------
        // BOTÕES
        // ---------------------------------------------------------

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF222222),
                    disabledContainerColor = Color(0xFF111111),
                    disabledContentColor = Color(0xFF555555)
                )
            ) {
                Text("VOLTAR PÁGINA")
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

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = CardBlack
            ),
            border = BorderStroke(
                width = 1.dp,
                color = BorderDark
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

        // ---------------------------------------------------------
        // WEBVIEW
        // ---------------------------------------------------------

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),

            factory = { context ->

                WebView(context).apply {

                    browser = this

                    // -------------------------------------------------
                    // CONFIGURAÇÕES DO WEBVIEW
                    // -------------------------------------------------

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)

                    // -------------------------------------------------
                    // COOKIES
                    // -------------------------------------------------

                    val cookieManager = CookieManager.getInstance()

                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(
                        this,
                        true
                    )

                    // -------------------------------------------------
                    // CHROME CLIENT
                    // -------------------------------------------------

                    webChromeClient = WebChromeClient()

                    // -------------------------------------------------
                    // NAVEGAÇÃO
                    // -------------------------------------------------

                    webViewClient = object : WebViewClient() {

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {

                            currentUrl = request.url.toString()

                            return false
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String
                        ) {

                            currentUrl = url

                            canGoBack = view.canGoBack()

                            if (!captured) {
                                status =
                                    "Página carregada. " +
                                            "Continue a navegação até " +
                                            "iniciar o download do PKG."
                            }
                        }
                    }

                    // -------------------------------------------------
                    // CAPTURA DO DOWNLOAD
                    // -------------------------------------------------

                    setDownloadListener { url,
                                          userAgent,
                                          contentDisposition,
                                          mimeType,
                                          contentLength ->

                        if (captured) {
                            return@setDownloadListener
                        }

                        // -------------------------------------------------
                        // VERIFICA URL
                        // -------------------------------------------------

                        val isHttp = url.startsWith(
                            "http://",
                            ignoreCase = true
                        )

                        val isHttps = url.startsWith(
                            "https://",
                            ignoreCase = true
                        )

                        if (!isHttp && !isHttps) {

                            status =
                                "Download detectado, " +
                                        "mas o endereço não é HTTP/HTTPS."

                            return@setDownloadListener
                        }

                        // -------------------------------------------------
                        // DESCOBRE O NOME DO ARQUIVO
                        // -------------------------------------------------

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

                        // -------------------------------------------------
                        // DOWNLOAD CAPTURADO
                        // -------------------------------------------------

                        captured = true

                        status =
                            "Link PKG capturado. " +
                                    "Validando arquivo..."

                        onCaptured(
                            PkgCaptureResult(
                                sourceUrl = sourceUrl,
                                directUrl = url,
                                fileName = finalFileName
                            )
                        )
                    }

                    // -------------------------------------------------
                    // ABRE URL INICIAL
                    // -------------------------------------------------

                    loadUrl(sourceUrl)
                }
            },

            // ---------------------------------------------------------
            // ATUALIZAÇÃO DO ANDROIDVIEW
            // ---------------------------------------------------------

            update = { view ->

                browser = view

                canGoBack = view.canGoBack()
            }
        )
    }
}
