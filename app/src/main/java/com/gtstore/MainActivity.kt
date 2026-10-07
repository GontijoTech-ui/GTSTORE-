package com.gtstore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

val PureBlack = Color(0xFF000000)
val CardBlack = Color(0xFF080808)
val BorderDark = Color(0xFF222222)
val RedAccent = Color(0xFFE50914)
val GreenLed = Color(0xFF35C759)
val RedLed = Color(0xFFFF3B30)
val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFFAAAAAA)

enum class GTStoreScreen {
    DASHBOARD,
    SERVIDOR,
    CATALOGO,
    CATALOGO_CADASTRADO,
    ADMIN,
    CONFIGURACOES
}

fun normalizeInputUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        "https://$trimmed"
    } else {
        trimmed
    }
}

class MainActivity : ComponentActivity() {

    private val gtStoreHttpServer: HttpServer
        get() = (application as GTStoreApplication).httpServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.init(this)

        // Inicia a escuta em tempo real dos pedidos no Firebase Realtime Database
        OrderManager.startListening()

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

@Composable
fun StatusCardLed(
    title: String,
    status: String,
    isOnline: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBlack),
        border = BorderStroke(1.dp, BorderDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextWhite
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) GreenLed else RedLed)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = status,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (isOnline) GreenLed else RedLed
                )
            }
        }
    }
}

@Composable
fun StatusCardSimple(
    title: String,
    status: String,
    statusColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBlack),
        border = BorderStroke(1.dp, BorderDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextWhite
                )
            }

            Text(
                text = status,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = statusColor
            )
        }
    }
}

@Composable
fun RedMenuButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RedAccent,
            contentColor = TextWhite
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun Dashboard(
    httpServer: HttpServer,
    onNavigate: (GTStoreScreen) -> Unit
) {
    val context = LocalContext.current
    var serverRunning by remember { mutableStateOf(httpServer.isRunning()) }

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
        while (isActive) {
            val isRunning = httpServer.isRunning()
            if (serverRunning != isRunning) {
                serverRunning = isRunning
            }
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
        item { Spacer(modifier = Modifier.height(24.dp)) }

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

        item { Spacer(modifier = Modifier.height(4.dp)) }

        item {
            RedMenuButton(
                text = "SERVIDOR",
                onClick = { onNavigate(GTStoreScreen.SERVIDOR) }
            )
        }

        item {
            RedMenuButton(
                text = "CATALOG MANAGER",
                onClick = { onNavigate(GTStoreScreen.CATALOGO) }
            )
        }

        item {
            RedMenuButton(
                text = "CATÁLOGO CADASTRADO",
                onClick = { onNavigate(GTStoreScreen.CATALOGO_CADASTRADO) }
            )
        }

        item {
            RedMenuButton(
                text = "ADMIN",
                onClick = { onNavigate(GTStoreScreen.ADMIN) }
            )
        }

        item {
            RedMenuButton(
                text = "CONFIGURAÇÕES",
                onClick = { onNavigate(GTStoreScreen.CONFIGURACOES) }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
fun GTStoreApp(
    httpServer: HttpServer,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(GTStoreScreen.DASHBOARD) }

    when (currentScreen) {
        GTStoreScreen.DASHBOARD -> Dashboard(
            httpServer = httpServer,
            onNavigate = { target: GTStoreScreen -> currentScreen = target }
        )
        GTStoreScreen.SERVIDOR -> ServerScreen(
            httpServer = httpServer,
            onStartServer = onStartServer,
            onStopServer = onStopServer,
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
        GTStoreScreen.CATALOGO -> CatalogManagerScreen(
            httpServer = httpServer,
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
        GTStoreScreen.CATALOGO_CADASTRADO -> RegisteredCatalogScreen(
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
        GTStoreScreen.ADMIN -> AdminScreen(
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
        GTStoreScreen.CONFIGURACOES -> SettingsScreen(
            onBack = { currentScreen = GTStoreScreen.
