package com.gtstore

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import android.webkit.DownloadListener

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

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

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),

            factory = { context ->

                WebView(context).apply {

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)

                    settings.userAgentString =
                        settings.userAgentString

                    CookieManager
                        .getInstance()
                        .setAcceptCookie(true)

                    CookieManager
                        .getInstance()
                        .setAcceptThirdPartyCookies(
                            this,
                            true
                        )

                    webChromeClient =
                        WebChromeClient()

                    webViewClient =
                        object : WebViewClient() {

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

                                if (!captured) {
                                    status =
                                        "Página carregada. Continue a navegação até iniciar o download do PKG."
                                }
                            }
                        }

                    setDownloadListener(
                        DownloadListener { url,
                                            userAgent,
                                            contentDisposition,
                                            mimeType,
                                            contentLength ->

                            if (captured) {
                                return@DownloadListener
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
                                    "Download detectado, mas o endereço não é HTTP/HTTPS."
                                return@DownloadListener
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

                            captured = true

                            status =
                                "Link PKG capturado. Validando arquivo..."

                            onCaptured(
                                PkgCaptureResult(
                                    sourceUrl =
                                        sourceUrl,

                                    directUrl =
                                        url,

                                    fileName =
                                        finalFileName
                                )
                            )
                        }
                    )

                    loadUrl(sourceUrl)
                }
            }
        )
    }
}