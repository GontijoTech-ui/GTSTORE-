package com.gtstore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// PALETA GTSTORE
// ============================================================

val PureBlack = Color(0xFF000000)
val CardBlack = Color(0xFF080808)
val BorderDark = Color(0xFF222222)
val RedAccent = Color(0xFFE50914)
val GreenLed = Color(0xFF35C759)
val RedLed = Color(0xFFFF3B30)
val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFFAAAAAA)

// ============================================================
// TELAS
// ============================================================

enum class GTStoreScreen {
    DASHBOARD,
    SERVIDOR,
    CATALOGO,
    ADMIN
}

// ============================================================
// ACTIVITY
// ============================================================

class MainActivity : ComponentActivity() {

    private val gtStoreHttpServer: HttpServer
        get() = (application as GTStoreApplication).httpServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val colorScheme = darkColorScheme(
                background = PureBlack,
                surface = PureBlack,
                primary = RedAccent,
                onPrimary = TextWhite,
                onBackground = TextWhite,
                onSurface = TextWhite
            )

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PureBlack
                ) {
                    GTStoreApp(
                        httpServer = gtStoreHttpServer,
                        onStartServer = ::startServerService,
                        onStopServer = ::stopServerService
                    )
                }
            }
        }
    }

    private fun startServerService() {
        val intent = Intent(this, GTStoreService::class.java).apply {
            action = GTStoreService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServerService() {
        val intent = Intent(this, GTStoreService::class.java).apply {
            action = GTStoreService.ACTION_STOP
        }

        startService(intent)
    }
}

// ============================================================
// APLICAÇÃO
// ============================================================

@Composable
fun GTStoreApp(
    httpServer: HttpServer,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    var currentScreen by remember {
        mutableStateOf(GTStoreScreen.DASHBOARD)
    }

    when (currentScreen) {

        GTStoreScreen.DASHBOARD -> {
            Dashboard(
                httpServer = httpServer,
                onNavigate = {
                    currentScreen = it
                }
            )
        }

        GTStoreScreen.SERVIDOR -> {
            ServerScreen(
                httpServer = httpServer,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                onBack = {
                    currentScreen = GTStoreScreen.DASHBOARD
                }
            )
        }

        GTStoreScreen.CATALOGO -> {
            CatalogManagerScreen(
                httpServer = httpServer,
                onBack = {
                    currentScreen = GTStoreScreen.DASHBOARD
                }
            )
        }

        GTStoreScreen.ADMIN -> {
            AdminScreen(
                httpServer = httpServer,
                onBack = {
                    currentScreen = GTStoreScreen.DASHBOARD
                }
            )
        }
    }
}

// ============================================================
// DASHBOARD
// ============================================================

@Composable
fun Dashboard(
    httpServer: HttpServer,
    onNavigate: (GTStoreScreen) -> Unit
) {
    val context = LocalContext.current

    var serverRunning by remember {
        mutableStateOf(httpServer.isRunning())
    }

    val logoBitmap = remember {
        try {
            context.assets.open("logo.jpg").use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            serverRunning = httpServer.isRunning()
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                if (logoBitmap != null) {
                    Image(
                        bitmap = logoBitmap.asImageBitmap(),
                        contentDescription = "GTSTORE Logo",
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(130.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "GTSTORE",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        color = RedAccent
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "GONTIJO TECH",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 6.sp,
                    color = Color(0xFFDDDDDD),
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            StatusCardLed(
                title = "SERVIDOR",
                status = if (serverRunning) "ONLINE" else "OFFLINE",
                isOnline = serverRunning
            )
        }

        item {
            StatusCardSimple(
                title = "CATÁLOGO",
                status = "PRONTO",
                statusColor = TextWhite
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        item {
            RedMenuButton(
                text = "SERVIDOR",
                onClick = {
                    onNavigate(GTStoreScreen.SERVIDOR)
                }
            )
        }

        item {
            RedMenuButton(
                text = "CATALOG MANAGER",
                onClick = {
                    onNavigate(GTStoreScreen.CATALOGO)
                }
            )
        }

        item {
            RedMenuButton(
                text = "ADMIN (SOLICITAÇÕES PIN)",
                onClick = {
                    onNavigate(GTStoreScreen.ADMIN)
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================================================
// SERVIDOR
// ============================================================

@Composable
fun ServerScreen(
    httpServer: HttpServer,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onBack: () -> Unit
) {
    var status by remember {
        mutableStateOf(httpServer.getStatus())
    }

    var message by remember {
        mutableStateOf("")
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = httpServer.getStatus()
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        item {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "PAINEL DO SERVIDOR",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = CardBlack
                ),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {

                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement =
