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
                text = "ADMIN (SOLICITAÇÕES PIN)",
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
            httpServer = httpServer,
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
        GTStoreScreen.CONFIGURACOES -> SettingsScreen(
            onBack = { currentScreen = GTStoreScreen.DASHBOARD }
        )
    }
}

@Composable
fun CatalogManagerScreen(
    httpServer: HttpServer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalogManager = remember(context) { CatalogManager(context) }
    val prefsSettings = remember { context.getSharedPreferences("GTSTORE_SETTINGS", Context.MODE_PRIVATE) }
    val prefsSources = remember { context.getSharedPreferences("GTSTORE_SOURCES_HISTORY", Context.MODE_PRIVATE) }

    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var captureSourceUrl by remember { mutableStateOf<String?>(null) }

    var recentSources by remember {
        mutableStateOf<List<String>>(
            try {
                val raw = prefsSources.getString("recent_sources", "[]") ?: "[]"
                val json = JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until json.length()) {
                    list.add(json.getString(i))
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        )
    }

    fun saveRecentSource(newUrl: String) {
        val clean = newUrl.trim()
        if (clean.isBlank()) return
        val currentList = recentSources.toMutableList()
        currentList.remove(clean)
        currentList.add(0, clean)
        if (currentList.size > 15) {
            currentList.removeAt(currentList.lastIndex)
        }
        recentSources = currentList
        val jsonArray = JSONArray()
        currentList.forEach { jsonArray.put(it) }
        prefsSources.edit().putString("recent_sources", jsonArray.toString()).apply()
    }

    fun deleteRecentSource(target: String) {
        val currentList = recentSources.toMutableList()
        currentList.remove(target)
        recentSources = currentList
        val jsonArray = JSONArray()
        currentList.forEach { jsonArray.put(it) }
        prefsSources.edit().putString("recent_sources", jsonArray.toString()).apply()
    }

    if (captureSourceUrl != null) {
        val exceptionsList = remember {
            val raw = prefsSettings.getString("domain_exceptions", "") ?: ""
            raw.split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        }

        PkgLinkCaptureScreen(
            sourceUrl = captureSourceUrl!!,
            allowedDomains = exceptionsList,
            onCaptured = { captureResult, onComplete ->
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            catalogManager.registerOrUpdateCaptured(
                                sourceUrl = captureResult.sourceUrl,
                                directUrl = captureResult.directUrl,
                                fileName = captureResult.fileName
                            )
                        } catch (e: Exception) {
                            CatalogManager.OperationResult(
                                success = false,
                                message = e.message ?: "Erro ao processar o PKG."
                            )
                        }
                    }

                    saving = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        onComplete()
                    }
                }
            },
            onCancel = { captureSourceUrl = null }
        )
        return
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
                    text = "CATALOG MANAGER",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "INSERIR URL",
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://...") }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val normalizedUrl = normalizeInputUrl(url)
                                if (normalizedUrl.isBlank()) {
                                    message = "Informe uma URL."
                                    return@Button
                                }

                                saving = true
                                message = "Analisando PKG remoto..."

                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        try {
                                            catalogManager.registerOrUpdate(normalizedUrl)
                                        } catch (e: Exception) {
                                            CatalogManager.OperationResult(
                                                success = false,
                                                message = e.message ?: "Erro ao processar a URL."
                                            )
                                        }
                                    }

                                    saving = false
                                    message = result.message
                                    if (result.success) {
                                        url = ""
                                    }
                                }
                            },
                            enabled = !saving,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (saving) "..." else "SALVAR DIRETA",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                val source = normalizeInputUrl(url)
                                if (source.isBlank()) {
                                    message = "Informe a URL inicial do site."
                                    return@Button
                                }
                                saveRecentSource(source)
                                message = ""
                                captureSourceUrl = source
                            },
                            enabled = !saving,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CAPTURAR LINK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (message.isNotBlank()) {
                        Text(
                            text = message,
                            color = if (saving) {
                                TextMuted
                            } else if (message.startsWith("URL atualizada") || message.startsWith("PKG reconhecido")) {
                                GreenLed
                            } else {
                                TextMuted
                            },
                            fontSize = 12.sp
                        )
                    }

                    if (recentSources.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "FONTES RECENTES (TOQUE PARA ABRIR):",
                            color = Color(0xFFDDDDDD),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for (recentUrl in recentSources) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF141414), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = recentUrl,
                                        color = Color(0xFF64B5F6),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                url = recentUrl
                                                saveRecentSource(recentUrl)
                                                captureSourceUrl = recentUrl
                                            }
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = "✕",
                                        color = Color(0xFF888888),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { deleteRecentSource(recentUrl) }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("VOLTAR", color = TextWhite, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun CatalogManagerItemCard(
    item: CatalogItem,
    catalogManager: CatalogManager,
    enabled: Boolean,
    onUpdate: () -> Unit
) {
    val iconBitmap = remember(item.iconFile) {
        val bytes = catalogManager.getIcon(item)
        if (bytes != null && bytes.isNotEmpty()) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBlack),
        border = BorderStroke(1.dp, BorderDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap.asImageBitmap(),
                        contentDescription = item.title,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFF181818), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.type.take(3),
                            color = RedAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.contentId,
                        color = Color(0xFF64B5F6),
                        fontSize = 12.sp
                    )
                    Text(
                        text = buildString {
                            append("Índice: ")
                            append(item.indexString)
                            if (item.version.isNotBlank()) {
                                append(" • v")
                                append(item.version)
                            }
                        },
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onUpdate,
                    enabled = enabled,
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ATUALIZAR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (item.fileName.isNotBlank()) {
                Text(
                    text = item.fileName,
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.sourceUrl.isBlank()) {
                Text(
                    text = "Sem URL de origem salva",
                    color = Color(0xFFFF9F0A),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun RegisteredCatalogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalogManager = remember(context) { CatalogManager(context) }
    val prefsSettings = remember { context.getSharedPreferences("GTSTORE_SETTINGS", Context.MODE_PRIVATE) }

    var allItems by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var captureSourceUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var sortAlphabetical by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        allItems = withContext(Dispatchers.IO) {
            catalogManager.getAll()
        }
    }

    val displayedItems = remember(allItems, searchQuery, sortAlphabetical) {
        val filtered = if (searchQuery.isBlank()) {
            allItems
        } else {
            val q = searchQuery.trim().lowercase()
            allItems.filter { item ->
                item.title.lowercase().contains(q) ||
                item.contentId.lowercase().contains(q) ||
                item.fileName.lowercase().contains(q) ||
                item.indexString.contains(q)
            }
        }

        if (sortAlphabetical) {
            filtered.sortedBy { it.title.lowercase() }
        } else {
            filtered.sortedBy { it.catalogIndex }
        }
    }

    if (captureSourceUrl != null) {
        val exceptionsList = remember {
            val raw = prefsSettings.getString("domain_exceptions", "") ?: ""
            raw.split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        }

        PkgLinkCaptureScreen(
            sourceUrl = captureSourceUrl!!,
            allowedDomains = exceptionsList,
            onCaptured = { captureResult, onComplete ->
                updating = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            catalogManager.registerOrUpdateCaptured(
                                sourceUrl = captureResult.sourceUrl,
                                directUrl = captureResult.directUrl,
                                fileName = captureResult.fileName
                            )
                        } catch (e: Exception) {
                            CatalogManager.OperationResult(
                                success = false,
                                message = e.message ?: "Erro ao atualizar."
                            )
                        }
                    }

                    updating = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }

                    allItems = withContext(Dispatchers.IO) { catalogManager.getAll() }

                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                }
            },
            onCancel = { captureSourceUrl = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(RedAccent)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "CATÁLOGO CADASTRADO",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${displayedItems.size} de ${allItems.size}",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("VOLTAR", color = TextWhite, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar título, CUSA ou índice...", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", color = TextMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (sortAlphabetical) "Ordem: Alfabética (A-Z)" else "Ordem: Índice (#)",
                    color = TextMuted,
                    fontSize = 12.sp
                )

                Button(
                    onClick = { sortAlphabetical = !sortAlphabetical },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (sortAlphabetical) "Alternar p/ Índice" else "Alternar p/ A-Z",
                        fontSize = 11.sp,
                        color = TextWhite,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(
                    text = message,
                    color = if (updating) TextMuted else GreenLed,
                    fontSize = 13.sp
                )
            }
        }

        if (displayedItems.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBlack),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "Nenhum PKG cadastrado." else "Nenhum resultado para \"$searchQuery\".",
                        modifier = Modifier.padding(20.dp),
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            items(
                items = displayedItems,
                key = { it.catalogIndex }
            ) { item ->
                CatalogManagerItemCard(
                    item = item,
                    catalogManager = catalogManager,
                    enabled = !updating,
                    onUpdate = {
                        if (item.sourceUrl.isBlank()) {
                            message = "Este item não possui URL de origem salva."
                            return@CatalogManagerItemCard
                        }
                        captureSourceUrl = item.sourceUrl
                    }
                )
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("VOLTAR", color = TextWhite, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("GTSTORE_SETTINGS", Context.MODE_PRIVATE) }
    val catalogManager = remember(context) { CatalogManager(context) }

    var exceptionsText by remember {
        mutableStateOf(prefs.getString("domain_exceptions", "") ?: "")
    }
    var message by remember { mutableStateOf("") }

    var executandoBackup by remember { mutableStateOf(false) }
    var restaurandoIcones by remember { mutableStateOf(false) }
    var statusIcones by remember { mutableStateOf("") }

    fun verificarPermissaoArmazenamento(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun abrirDefinicoesPermissao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
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
                    text = "CONFIGURAÇÕES",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }

        // Card Exceções de Domínio
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "EXCEÇÕES DE DOMÍNIO",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Insira os domínios permitidos durante a captura de link (separados por vírgula ou linha). Se o site redirecionar para um destes, a navegação não será bloqueada.",
                        color = TextMuted,
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = exceptionsText,
                        onValueChange = {
                            exceptionsText = it
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 4,
                        maxLines = 8,
                        placeholder = {
                            Text("exemplo: mediafire.com, mega.nz, 1fichier.com")
                        }
                    )

                    Button(
                        onClick = {
                            prefs.edit().putString("domain_exceptions", exceptionsText.trim()).apply()
                            message = "Exceções salvas com sucesso!"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "SALVAR CONFIGURAÇÃO",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (message.isNotBlank()) {
                        Text(
                            text = message,
                            color = GreenLed,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Card Backup do Catálogo (ZIP Completo: JSON + Ícones)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "BACKUP DO CATÁLOGO",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Exporte todos os jogos e capas num único arquivo ZIP para Download/logs/gtstore_complete_backup.zip.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!verificarPermissaoArmazenamento()) {
                                    Toast.makeText(context, "Conceda permissão de armazenamento primeiro!", Toast.LENGTH_LONG).show()
                                    abrirDefinicoesPermissao()
                                    return@Button
                                }
                                executandoBackup = true
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        catalogManager.exportCatalogZipBackup()
                                    }
                                    executandoBackup = false
                                    Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = !executandoBackup && !restaurandoIcones,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("EXPORTAR ZIP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (!verificarPermissaoArmazenamento()) {
                                    Toast.makeText(context, "Conceda permissão de armazenamento primeiro!", Toast.LENGTH_LONG).show()
                                    abrirDefinicoesPermissao()
                                    return@Button
                                }
                                executandoBackup = true
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        catalogManager.importCatalogZipBackup()
                                    }
                                    executandoBackup = false
                                    Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = !executandoBackup && !restaurandoIcones,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (executandoBackup) "..." else "RESTAURAR ZIP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Botão para descarregar capas oficiais via TMDB (CDN Sony)
                    Button(
                        onClick = {
                            if (!restaurandoIcones) {
                                restaurandoIcones = true
                                statusIcones = "A verificar capas em falta..."
                                scope.launch {
                                    val total = catalogManager.restaurarIconesFaltantes { atual, totalItens, nome ->
                                        statusIcones = "Recuperando ($atual/$totalItens): $nome"
                                    }
                                    restaurandoIcones = false
                                    statusIcones = "Concluído! $total capas recuperadas."
                                    Toast.makeText(context, statusIcones, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !executandoBackup && !restaurandoIcones,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (restaurandoIcones) "A RECUPERAR..." else "RECUPERAR ÍCONES (TMDB)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                    }

                    if (statusIcones.isNotBlank()) {
                        Text(
                            text = statusIcones,
                            color = if (restaurandoIcones) Color(0xFF64B5F6) else GreenLed,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Card Logs do Sistema
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "LOGS DO SISTEMA",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Arquivo: ${AppLogger.getLogPath()}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val text = AppLogger.getLogContent()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("GTStore Log", text))
                                Toast.makeText(context, "Log copiado!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("COPIAR LOG", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val text = AppLogger.getLogContent()
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Enviar Log"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("COMPARTILHAR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            AppLogger.clearLog()
                            Toast.makeText(context, "Log limpo!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("LIMPAR ARQUIVO DE LOG", color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }

    }
}

@Composable
fun ServerScreen(
    httpServer: HttpServer,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onBack: () -> Unit
) {
    var status by remember { mutableStateOf(httpServer.getStatus()) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (isActive) {
            val freshStatus = httpServer.getStatus()
            if (status != freshStatus) {
                status = freshStatus
            }
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
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(if (status.running) GreenLed else RedLed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (status.running) "SERVIDOR ONLINE" else "SERVIDOR OFFLINE",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (status.running) GreenLed else RedLed
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Porta: ${status.port}",
                        color = TextWhite,
                        fontSize = 15.sp
                    )

                    Text(
                        text = "Endereço Local: ${status.localAddress}",
                        color = TextWhite,
                        fontSize = 15.sp
                    )

                    if (status.running) {
                        Text(
                            text = "URL: http://${status.localAddress}:${status.port}",
                            color = Color(0xFF64B5F6),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "Conexões ativas: ${status.activeConnections}",
                        color = TextMuted,
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (httpServer.isRunning()) {
                                onStopServer()
                                message = "Solicitação para parar o servidor enviada."
                            } else {
                                onStartServer()
                                message = "Solicitação para iniciar o servidor enviada."
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (status.running) Color(0xFF333333) else RedAccent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (status.running) "PARAR SERVIDOR" else "INICIAR SERVIDOR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (message.isNotBlank()) {
                        Text(
                            text = message,
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = { status = httpServer.getStatus() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("ATUALIZAR STATUS", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("VOLTAR", color = TextWhite)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminScreen(
    httpServer: HttpServer,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pinRequests by remember { mutableStateOf<List<PinRequest>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val list = httpServer.getPinRequests()
            if (pinRequests != list) {
                pinRequests = list
            }
            delay(1500)
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
                    text = "SOLICITAÇÕES DE PIN",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBlack),
                border = BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (pinRequests.isNotEmpty()) GreenLed else RedLed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (pinRequests.isNotEmpty()) "FILA ATIVA" else "AGUARDANDO SOLICITAÇÕES",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pinRequests.isNotEmpty()) GreenLed else TextMuted
                        )
                    }

                    Text(
                        text = "Solicitações pendentes: ${pinRequests.size}",
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Validade de cada chave: 10 minutos",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (pinRequests.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBlack),
                    border = BorderStroke(1.dp, BorderDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Nenhuma solicitação no momento...",
                        modifier = Modifier.padding(20.dp),
                        color = TextMuted,
                        fontSize = 15.sp
                    )
                }
            }
        } else {
            items(
                items = pinRequests,
                key = { it.id }
            ) { req ->
                val elapsed = System.currentTimeMillis() - req.createdAt
                val remaining = ((HttpServer.PIN_TIMEOUT_MS - elapsed) / 1000L).coerceAtLeast(0L)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBlack),
                    border = BorderStroke(
                        1.dp,
                        if (req.isExpired) BorderDark else Color(0xFF331114)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = req.gameTitle,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite,
                                modifier = Modifier.weight(1f)
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (req.isExpired) RedLed else GreenLed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (req.isExpired) "EXPIRADO" else "${remaining / 60}m ${remaining % 60}s",
                                    color = if (req.isExpired) RedLed else Color(0xFFFF9F0A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "Código / CUSA: ${req.gameKey}",
                            fontSize = 13.sp,
                            color = Color(0xFF64B5F6),
                            fontWeight = FontWeight.SemiBold
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)),
                            border = BorderStroke(1.dp, BorderDark),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PIN:",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted
                                )

                                Text(
                                    text = req.pin,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    color = if (req.isExpired) RedLed else GreenLed
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Seu PIN de download para o jogo *${req.gameTitle}* (${req.gameKey}) na GTSTORE é: *${req.pin}*\n\n⚠️ Válido por 10 minutos."
                                        )
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Enviar PIN no WhatsApp")
                                    )
                                },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "WHATSAPP",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("PIN PS4", req.pin))
                                    Toast.makeText(context, "PIN copiado!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "COPIAR",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("VOLTAR", color = TextWhite)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
