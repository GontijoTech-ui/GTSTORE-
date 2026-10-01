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

        MaterialTheme(  
            colorScheme = colorScheme  
        ) {  

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

    val intent =  
        Intent(  
            this,  
            GTStoreService::class.java  
        ).apply {  
            action =  
                GTStoreService.ACTION_START  
        }  

    if (  
        Build.VERSION.SDK_INT >=  
        Build.VERSION_CODES.O  
    ) {  

        startForegroundService(intent)  

    } else {  

        startService(intent)  
    }  
}  

private fun stopServerService() {  

    val intent =  
        Intent(  
            this,  
            GTStoreService::class.java  
        ).apply {  
            action =  
                GTStoreService.ACTION_STOP  
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
    mutableStateOf(  
        GTStoreScreen.DASHBOARD  
    )  
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
                currentScreen =  
                    GTStoreScreen.DASHBOARD  
            }  
        )  
    }  

    GTStoreScreen.CATALOGO -> {  

        CatalogManagerScreen(  
            httpServer = httpServer,  
            onBack = {  
                currentScreen =  
                    GTStoreScreen.DASHBOARD  
            }  
        )  
    }  

    GTStoreScreen.ADMIN -> {  

        AdminScreen(  
            httpServer = httpServer,  
            onBack = {  
                currentScreen =  
                    GTStoreScreen.DASHBOARD  
            }  
        )  
    }  
}  



}


// ============================================================

// ITEM DO CATALOG MANAGER

// ============================================================


@Composable

fun CatalogManagerItemCard(

item: CatalogItem,

enabled: Boolean,

onUpdate: () -> Unit

) {


Card(  
    modifier =  
        Modifier.fillMaxWidth(),  

    colors =  
        CardDefaults.cardColors(  
            containerColor =  
                CardBlack  
        ),  

    border =  
        BorderStroke(  
            1.dp,  
            BorderDark  
        ),  

    shape =  
        RoundedCornerShape(  
            10.dp  
        )  
) {  

    Column(  
        modifier =  
            Modifier.padding(  
                16.dp  
            ),  

        verticalArrangement =  
            Arrangement.spacedBy(  
                8.dp  
            )  
    ) {  

        Row(  
            modifier =  
                Modifier.fillMaxWidth(),  

            horizontalArrangement =  
                Arrangement.spacedBy(  
                    10.dp  
                ),  

            verticalAlignment =  
                Alignment.CenterVertically  
        ) {  

            Column(  
                modifier =  
                    Modifier.weight(1f)  
            ) {  

                Text(  
                    text =  
                        item.title,  

                    color =  
                        TextWhite,  

                    fontSize =  
                        16.sp,  

                    fontWeight =  
                        FontWeight.Bold  
                )  

                Text(  
                    text =  
                        item.contentId,  

                    color =  
                        Color(0xFF64B5F6),  

                    fontSize =  
                        12.sp  
                )  

                Text(  
                    text =  
                        buildString {  

                            append(  
                                "Índice: "  
                            )  

                            append(  
                                item.indexString  
                            )  

                            if (  
                                item.version.isNotBlank()  
                            ) {  

                                append(  
                                    " • v"  
                                )  

                                append(  
                                    item.version  
                                )  
                            }  
                        },  

                    color =  
                        TextMuted,  

                    fontSize =  
                        12.sp  
                )  

                if (  
                    item.fileName.isNotBlank()  
                ) {  

                    Text(  
                        text =  
                            item.fileName,  

                        color =  
                            TextMuted,  

                        fontSize =  
                            12.sp  
                    )  
                }  
            }  

            Button(  
                onClick =  
                    onUpdate,  

                enabled =  
                    enabled,  

                modifier =  
                    Modifier.height(  
                        42.dp  
                    ),  

                colors =  
                    ButtonDefaults.buttonColors(  
                        containerColor =  
                            RedAccent  
                    ),  

                shape =  
                    RoundedCornerShape(  
                        7.dp  
                    )  
            ) {  

                Text(  
                    text =  
                        "ATUALIZAR",  

                    fontSize =  
                        11.sp,  

                    fontWeight =  
                        FontWeight.Bold  
                )  
            }  
        }  

        if (  
            item.sourceUrl.isBlank()  
        ) {  

            Text(  
                text =  
                    "Sem URL de origem salva",  

                color =  
                    Color(0xFFFF9F0A),  

                fontSize =  
                    11.sp  
            )  
        }  
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


val context =  
    LocalContext.current  

var serverRunning by remember {  
    mutableStateOf(  
        httpServer.isRunning()  
    )  
}  

val logoBitmap =  
    remember {  

        try {  

            context.assets  
                .open("logo.jpg")  
                .use { inputStream ->  

                    BitmapFactory  
                        .decodeStream(  
                            inputStream  
                        )  
                }  

        } catch (_: Exception) {  

            null  
        }  
    }  

LaunchedEffect(Unit) {  

    while (true) {  

        serverRunning =  
            httpServer.isRunning()  

        delay(1000)  
    }  
}  

LazyColumn(  
    modifier =  
        Modifier  
            .fillMaxSize()  
            .background(  
                PureBlack  
            )  
            .padding(  
                horizontal = 22.dp,  
                vertical = 20.dp  
            ),  

    verticalArrangement =  
        Arrangement.spacedBy(  
            16.dp  
        ),  

    horizontalAlignment =  
        Alignment.CenterHorizontally  
) {  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    24.dp  
                )  
        )  
    }  

    item {  

        Column(  
            horizontalAlignment =  
                Alignment.CenterHorizontally,  

            modifier =  
                Modifier  
                    .fillMaxWidth()  
                    .padding(  
                        bottom = 10.dp  
                    )  
        ) {  

            if (  
                logoBitmap != null  
            ) {  

                Image(  
                    bitmap =  
                        logoBitmap  
                            .asImageBitmap(),  

                    contentDescription =  
                        "GTSTORE Logo",  

                    modifier =  
                        Modifier  
                            .fillMaxWidth(  
                                0.85f  
                            )  
                            .height(  
                                130.dp  
                            ),  

                    contentScale =  
                        ContentScale.Fit  
                )  

            } else {  

                Text(  
                    text =  
                        "GTSTORE",  

                    fontSize =  
                        36.sp,  

                    fontWeight =  
                        FontWeight.Black,  

                    color =  
                        RedAccent  
                )  
            }  

            Spacer(  
                modifier =  
                    Modifier.height(  
                        8.dp  
                    )  
            )  

            Text(  
                text =  
                    "GONTIJO TECH",  

                fontSize =  
                    18.sp,  

                fontWeight =  
                    FontWeight.ExtraBold,  

                letterSpacing =  
                    6.sp,  

                color =  
                    Color(0xFFDDDDDD),  

                textAlign =  
                    TextAlign.Center  
            )  
        }  
    }  

    item {  

        StatusCardLed(  
            title =  
                "SERVIDOR",  

            status =  
                if (  
                    serverRunning  
                ) {  
                    "ONLINE"  
                } else {  
                    "OFFLINE"  
                },  

            isOnline =  
                serverRunning  
        )  
    }  

    item {  

        StatusCardSimple(  
            title =  
                "CATÁLOGO",  

            status =  
                "PRONTO",  

            statusColor =  
                TextWhite  
        )  
    }  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    4.dp  
                )  
        )  
    }  

    item {  

        RedMenuButton(  
            text =  
                "SERVIDOR",  

            onClick = {  
                onNavigate(  
                    GTStoreScreen.SERVIDOR  
                )  
            }  
        )  
    }  

    item {  

        RedMenuButton(  
            text =  
                "CATALOG MANAGER",  

            onClick = {  
                onNavigate(  
                    GTStoreScreen.CATALOGO  
                )  
            }  
        )  
    }  

    item {  

        RedMenuButton(  
            text =  
                "ADMIN (SOLICITAÇÕES PIN)",  

            onClick = {  
                onNavigate(  
                    GTStoreScreen.ADMIN  
                )  
            }  
        )  
    }  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    24.dp  
                )  
        )  
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
    mutableStateOf(  
        httpServer.getStatus()  
    )  
}  

var message by remember {  
    mutableStateOf("")  
}  

LaunchedEffect(Unit) {  

    while (true) {  

        status =  
            httpServer.getStatus()  

        delay(1000)  
    }  
}  

LazyColumn(  
    modifier =  
        Modifier  
            .fillMaxSize()  
            .background(  
                PureBlack  
            )  
            .padding(16.dp),  

    verticalArrangement =  
        Arrangement.spacedBy(  
            14.dp  
        )  
) {  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    16.dp  
                )  
        )  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically,  

            modifier =  
                Modifier.padding(  
                    vertical = 8.dp  
                )  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(14.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            RedAccent  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        10.dp  
                    )  
            )  

            Text(  
                text =  
                    "PAINEL DO SERVIDOR",  

                fontSize =  
                    22.sp,  

                fontWeight =  
                    FontWeight.Bold,  

                color =  
                    TextWhite  
            )  
        }  
    }  

    item {  

        Card(  
            modifier =  
                Modifier.fillMaxWidth(),  

            colors =  
                CardDefaults.cardColors(  
                    containerColor =  
                        CardBlack  
                ),  

            border =  
                BorderStroke(  
                    1.dp,  
                    BorderDark  
                ),  

            shape =  
                RoundedCornerShape(  
                    10.dp  
                )  
        ) {  

            Column(  
                modifier =  
                    Modifier.padding(  
                        18.dp  
                    ),  

                verticalArrangement =  
                    Arrangement.spacedBy(  
                        10.dp  
                    )  
            ) {  

                Row(  
                    verticalAlignment =  
                        Alignment.CenterVertically  
                ) {  

                    Box(  
                        modifier =  
                            Modifier  
                                .size(14.dp)  
                                .clip(  
                                    CircleShape  
                                )  
                                .background(  
                                    if (  
                                        status.running  
                                    ) {  
                                        GreenLed  
                                    } else {  
                                        RedLed  
                                    }  
                                )  
                    )  

                    Spacer(  
                        modifier =  
                            Modifier.width(  
                                10.dp  
                            )  
                    )  

                    Text(  
                        text =  
                            if (  
                                status.running  
                            ) {  
                                "SERVIDOR ONLINE"  
                            } else {  
                                "SERVIDOR OFFLINE"  
                            },  

                        fontSize =  
                            18.sp,  

                        fontWeight =  
                            FontWeight.Bold,  

                        color =  
                            if (  
                                status.running  
                            ) {  
                                GreenLed  
                            } else {  
                                RedLed  
                            }  
                    )  
                }  

                Spacer(  
                    modifier =  
                        Modifier.height(  
                            4.dp  
                        )  
                )  

                Text(  
                    text =  
                        "Porta: ${status.port}",  

                    color =  
                        TextWhite,  

                    fontSize =  
                        15.sp  
                )  

                Text(  
                    text =  
                        "Endereço Local: ${status.localAddress}",  

                    color =  
                        TextWhite,  

                    fontSize =  
                        15.sp  
                )  

                if (  
                    status.running  
                ) {  

                    Text(  
                        text =  
                            "URL: http://${status.localAddress}:${status.port}",  

                        color =  
                            Color(0xFF64B5F6),  

                        fontSize =  
                            15.sp,  

                        fontWeight =  
                            FontWeight.SemiBold  
                    )  
                }  

                Text(  
                    text =  
                        "Conexões ativas: ${status.activeConnections}",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        14.sp  
                )  

                Spacer(  
                    modifier =  
                        Modifier.height(  
                            10.dp  
                        )  
                )  

                Button(  
                    onClick = {  

                        if (  
                            httpServer.isRunning()  
                        ) {  

                            onStopServer()  

                            message =  
                                "Solicitação para parar o servidor enviada."  

                        } else {  

                            onStartServer()  

                            message =  
                                "Solicitação para iniciar o servidor enviada."  
                        }  
                    },  

                    modifier =  
                        Modifier  
                            .fillMaxWidth()  
                            .height(  
                                50.dp  
                            ),  

                    colors =  
                        ButtonDefaults.buttonColors(  
                            containerColor =  
                                if (  
                                    status.running  
                                ) {  
                                    Color(  
                                        0xFF333333  
                                    )  
                                } else {  
                                    RedAccent  
                                }  
                        ),  

                    shape =  
                        RoundedCornerShape(  
                            8.dp  
                        )  
                ) {  

                    Text(  
                        text =  
                            if (  
                                status.running  
                            ) {  
                                "PARAR SERVIDOR"  
                            } else {  
                                "INICIAR SERVIDOR"  
                            },  

                        fontWeight =  
                            FontWeight.Bold,  

                        fontSize =  
                            15.sp  
                    )  
                }  

                if (  
                    message.isNotBlank()  
                ) {  

                    Text(  
                        text =  
                            message,  

                        color =  
                            TextMuted,  

                        fontSize =  
                            13.sp  
                    )  
                }  

                Button(  
                    onClick = {  

                        status =  
                            httpServer.getStatus()  
                    },  

                    modifier =  
                        Modifier  
                            .fillMaxWidth()  
                            .height(  
                                48.dp  
                            ),  

                    colors =  
                        ButtonDefaults.buttonColors(  
                            containerColor =  
                                Color(  
                                    0xFF1A1A1A  
                                )  
                        ),  

                    shape =  
                        RoundedCornerShape(  
                            8.dp  
                        )  
                ) {  

                    Text(  
                        "ATUALIZAR STATUS",  
                        fontWeight =  
                            FontWeight.Bold  
                    )  
                }  

                Button(  
                    onClick =  
                        onBack,  

                    modifier =  
                        Modifier  
                            .fillMaxWidth()  
                            .height(  
                                48.dp  
                            ),  

                    colors =  
                        ButtonDefaults.buttonColors(  
                            containerColor =  
                                Color(  
                                    0xFF141414  
                                )  
                        ),  

                    shape =  
                        RoundedCornerShape(  
                            8.dp  
                        )  
                ) {  

                    Text(  
                        "VOLTAR",  
                        color =  
                            TextWhite  
                    )  
                }  
            }  
        }  
    }  
}  



}


// ============================================================

// CATALOG MANAGER

// ============================================================


@Composable

fun CatalogManagerScreen(

httpServer: HttpServer,

onBack: () -> Unit

) {


val context =  
    LocalContext.current  

val scope =  
    rememberCoroutineScope()  

val catalogManager =  
    remember(context) {  
        CatalogManager(context)  
    }  

var url by remember {  
    mutableStateOf("")  
}  

var message by remember {  
    mutableStateOf("")  
}  

var saving by remember {  
    mutableStateOf(false)  
}  

var items by remember {  
    mutableStateOf(  
        catalogManager.getAll()  
    )  
}  

var captureSourceUrl by remember {  
    mutableStateOf<String?>(null)  
}  

// ========================================================  
// NAVEGADOR INTERNO  
// ========================================================  

if (  
    captureSourceUrl != null  
) {  

    PkgLinkCaptureScreen(  
        sourceUrl =  
            captureSourceUrl!!,  

        onCaptured = {  
            captureResult ->  

            captureSourceUrl =  
                null  

            saving =  
                true  

            message =  
                "Link capturado. Validando PKG..."  

            scope.launch {  

                val result =  
                    withContext(  
                        Dispatchers.IO  
                    ) {  

                        try {  

                            catalogManager  
                                .registerOrUpdateCaptured(  
                                    sourceUrl =  
                                        captureResult.sourceUrl,  

                                    directUrl =  
                                        captureResult.directUrl,  

                                    fileName =  
                                        captureResult.fileName  
                                )  

                        } catch (  
                            e: Exception  
                        ) {  

                            CatalogManager  
                                .OperationResult(  
                                    success =  
                                        false,  

                                    message =  
                                        e.message  
                                            ?: "Erro ao processar o PKG."  
                                )  
                        }  
                    }  

                saving =  
                    false  

                message =  
                    result.message  

                if (  
                    result.success  
                ) {  

                    items =  
                        catalogManager  
                            .getAll()  

                    url =  
                        ""  
                }  
            }  
        },  

        onCancel = {  

            captureSourceUrl =  
                null  
        }  
    )  

    return  
}  

// ========================================================  
// TELA PRINCIPAL  
// ========================================================  

LazyColumn(  
    modifier =  
        Modifier  
            .fillMaxSize()  
            .background(  
                PureBlack  
            )  
            .padding(16.dp),  

    verticalArrangement =  
        Arrangement.spacedBy(  
            14.dp  
        )  
) {  

    // ====================================================  
    // CABEÇALHO  
    // ====================================================  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    16.dp  
                )  
        )  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically,  

            modifier =  
                Modifier.padding(  
                    vertical = 8.dp  
                )  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(14.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            RedAccent  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        10.dp  
                    )  
            )  

            Text(  
                text =  
                    "CATALOG MANAGER",  

                fontSize =  
                    22.sp,  

                fontWeight =  
                    FontWeight.Bold,  

                color =  
                    TextWhite  
            )  
        }  
    }  

    // ====================================================  
    // URL  
    // ====================================================  

    item {  

        Card(  
            modifier =  
                Modifier.fillMaxWidth(),  

            colors =  
                CardDefaults.cardColors(  
                    containerColor =  
                        CardBlack  
                ),  

            border =  
                BorderStroke(  
                    1.dp,  
                    BorderDark  
                ),  

            shape =  
                RoundedCornerShape(  
                    10.dp  
                )  
        ) {  

            Column(  
                modifier =  
                    Modifier.padding(  
                        18.dp  
                    ),  

                verticalArrangement =  
                    Arrangement.spacedBy(  
                        12.dp  
                    )  
            ) {  

                Text(  
                    text =  
                        "URL EXTERNA DO PKG",  

                    color =  
                        TextWhite,  

                    fontSize =  
                        16.sp,  

                    fontWeight =  
                        FontWeight.Bold  
                )  

                Text(  
                    text =  
                        "URL direta do PKG ou URL inicial do site.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                OutlinedTextField(  
                    value =  
                        url,  

                    onValueChange = {  
                        url =  
                            it  

                        message =  
                            ""  
                    },  

                    modifier =  
                        Modifier.fillMaxWidth(),  

                    singleLine =  
                        false,  

                    minLines =  
                        3,  

                    maxLines =  
                        5,  

                    label = {  
                        Text(  
                            "URL"  
                        )  
                    }  
                )  

                // ========================================  
                // SALVAR URL DIRETA  
                // ========================================  

                Button(  
                    onClick = {  

                        val normalizedUrl =  
                            url.trim()  

                        if (  
                            normalizedUrl.isBlank()  
                        ) {  

                            message =  
                                "Informe uma URL."  

                            return@Button  
                        }  

                        if (  
                            !normalizedUrl  
                                .startsWith(  
                                    "http://"  
                                ) &&  
                            !normalizedUrl  
                                .startsWith(  
                                    "https://"  
                                )  
                        ) {  

                            message =  
                                "A URL deve começar com http:// ou https://."  

                            return@Button  
                        }  

                        saving =  
                            true  

                        message =  
                            "Analisando PKG remoto..."  

                        scope.launch {  

                            val result =  
                                withContext(  
                                    Dispatchers.IO  
                                ) {  

                                    try {  

                                        catalogManager  
                                            .registerOrUpdate(  
                                                normalizedUrl  
                                            )  

                                    } catch (  
                                        e: Exception  
                                    ) {  

                                        CatalogManager  
                                            .OperationResult(  
                                                success =  
                                                    false,  

                                                message =  
                                                    e.message  
                                                        ?: "Erro ao processar a URL."  
                                            )  
                                    }  
                                }  

                            saving =  
                                false  

                            message =  
                                result.message  

                            if (  
                                result.success  
                            ) {  

                                items =  
                                    catalogManager  
                                        .getAll()  

                                url =  
                                    ""  
                            }  
                        }  
                    },  

                    enabled =  
                        !saving,  

                    modifier =  
                        Modifier  
                            .fillMaxWidth()  
                            .height(  
                                50.dp  
                            ),  

                    colors =  
                        ButtonDefaults.buttonColors(  
                            containerColor =  
                                RedAccent  
                        ),  

                    shape =  
                        RoundedCornerShape(  
                            8.dp  
                        )  
                ) {  

                    Text(  
                        text =  
                            if (  
                                saving  
                            ) {  
                                "PROCESSANDO..."  
                            } else {  
                                "SALVAR URL DIRETA"  
                            },  

                        fontWeight =  
                            FontWeight.Bold,  

                        fontSize =  
                            15.sp  
                    )  
                }  

                // ========================================  
                // CAPTURAR LINK  
                // ========================================  

                Button(  
                    onClick = {  

                        val source =  
                            url.trim()  

                        if (  
                            source.isBlank()  
                        ) {  

                            message =  
                                "Informe a URL inicial do site."  

                            return@Button  
                        }  

                        if (  
                            !source  
                                .startsWith(  
                                    "http://"  
                                ) &&  
                            !source  
                                .startsWith(  
                                    "https://"  
                                )  
                        ) {  

                            message =  
                                "A URL deve começar com http:// ou https://."  

                            return@Button  
                        }  

                        message =  
                            ""  

                        captureSourceUrl =  
                            source  
                    },  

                    enabled =  
                        !saving,  

                    modifier =  
                        Modifier  
                            .fillMaxWidth()  
                            .height(  
                                50.dp  
                            ),  

                    colors =  
                        ButtonDefaults.buttonColors(  
                            containerColor =  
                                Color(  
                                    0xFF222222  
                                )  
                        ),  

                    shape =  
                        RoundedCornerShape(  
                            8.dp  
                        )  
                ) {  

                    Text(  
                        text =  
                            "CAPTURAR LINK",  

                        fontWeight =  
                            FontWeight.Bold,  

                        fontSize =  
                            15.sp  
                    )  
                }  

                if (  
                    message.isNotBlank()  
                ) {  

                    Text(  
                        text =  
                            message,  

                        color =  
                            if (  
                                saving  
                            ) {  

                                TextMuted  

                            } else if (  
                                message.startsWith(  
                                    "URL atualizada"  
                                ) ||  
                                message.startsWith(  
                                    "PKG reconhecido"  
                                )  
                            ) {  

                                GreenLed  

                            } else {  

                                TextMuted  
                            },  

                        fontSize =  
                            13.sp  
                    )  
                }  
            }  
        }  
    }  

    // ====================================================  
    // CATÁLOGO CADASTRADO  
    // ====================================================  

    item {  

        Text(  
            text =  
                "CATÁLOGO CADASTRADO",  

            color =  
                TextWhite,  

            fontSize =  
                18.sp,  

            fontWeight =  
                FontWeight.Bold,  

            modifier =  
                Modifier.padding(  
                    top = 6.dp  
                )  
        )  
    }  

    // ====================================================  
    // LISTA  
    // ====================================================  

    if (  
        items.isEmpty()  
    ) {  

        item {  

            Card(  
                modifier =  
                    Modifier.fillMaxWidth(),  

                colors =  
                    CardDefaults.cardColors(  
                        containerColor =  
                            CardBlack  
                    ),  

                border =  
                    BorderStroke(  
                        1.dp,  
                        BorderDark  
                    ),  

                shape =  
                    RoundedCornerShape(  
                        10.dp  
                    )  
            ) {  

                Text(  
                    text =  
                        "Nenhum PKG cadastrado.",  

                    modifier =  
                        Modifier.padding(  
                            20.dp  
                        ),  

                    color =  
                        TextMuted,  

                    fontSize =  
                        14.sp  
                )  
            }  
        }  

    } else {  

        items(  
            items =  
                items,  

            key = {  
                it.catalogIndex  
            }  
        ) { item ->  

            CatalogManagerItemCard(  
                item =  
                    item,  

                enabled =  
                    !saving,  

                onUpdate = {  

                    if (  
                        item.sourceUrl  
                            .isBlank()  
                    ) {  

                        message =  
                            "Este item não possui URL de origem salva. Cadastre novamente usando CAPTURAR LINK."  

                        return@CatalogManagerItemCard  
                    }  

                    captureSourceUrl =  
                        item.sourceUrl  
                }  
            )  
        }  
    }  

    // ====================================================  
    // COMPORTAMENTO  
    // ====================================================  

    item {  

        Card(  
            modifier =  
                Modifier.fillMaxWidth(),  

            colors =  
                CardDefaults.cardColors(  
                    containerColor =  
                        Color(  
                            0xFF0A0A0A  
                        )  
                ),  

            border =  
                BorderStroke(  
                    1.dp,  
                    BorderDark  
                ),  

            shape =  
                RoundedCornerShape(  
                    10.dp  
                )  
        ) {  

            Column(  
                modifier =  
                    Modifier.padding(  
                        18.dp  
                    ),  

                verticalArrangement =  
                    Arrangement.spacedBy(  
                        8.dp  
                    )  
            ) {  

                Text(  
                    text =  
                        "COMPORTAMENTO",  

                    color =  
                        TextWhite,  

                    fontSize =  
                        15.sp,  

                    fontWeight =  
                        FontWeight.Bold  
                )  

                Text(  
                    text =  
                        "• URL direta: o PKG é analisado diretamente.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                Text(  
                    text =  
                        "• Capturar link: o site abre dentro do GTSTORE.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                Text(  
                    text =  
                        "• CAPTCHA: resolvido manualmente no navegador.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                Text(  
                    text =  
                        "• O link direto capturado é validado pelo RemotePkgReader.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                Text(  
                    text =  
                        "• O PKG completo não será armazenado no Android.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  
            }  
        }  
    }  

    // ====================================================  
    // VOLTAR  
    // ====================================================  

    item {  

        Button(  
            onClick =  
                onBack,  

            modifier =  
                Modifier  
                    .fillMaxWidth()  
                    .height(  
                        48.dp  
                    ),  

            colors =  
                ButtonDefaults.buttonColors(  
                    containerColor =  
                        Color(  
                            0xFF141414  
                        )  
                ),  

            shape =  
                RoundedCornerShape(  
                    8.dp  
                )  
        ) {  

            Text(  
                "VOLTAR",  

                color =  
                    TextWhite,  

                fontWeight =  
                    FontWeight.Bold  
            )  
        }  

        Spacer(  
            modifier =  
                Modifier.height(  
                    16.dp  
                )  
        )  
    }  
}  



}


// ============================================================

// ADMIN

// ============================================================


@Composable

fun AdminScreen(

httpServer: HttpServer,

onBack: () -> Unit

) {


val context =  
    LocalContext.current  

var pinRequests by remember {  
    mutableStateOf<List<PinRequest>>(  
        emptyList()  
    )  
}  

LaunchedEffect(Unit) {  

    while (true) {  

        pinRequests =  
            httpServer.getPinRequests()  

        delay(1500)  
    }  
}  

LazyColumn(  
    modifier =  
        Modifier  
            .fillMaxSize()  
            .background(  
                PureBlack  
            )  
            .padding(16.dp),  

    verticalArrangement =  
        Arrangement.spacedBy(  
            14.dp  
        )  
) {  

    item {  

        Spacer(  
            modifier =  
                Modifier.height(  
                    16.dp  
                )  
        )  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically,  

            modifier =  
                Modifier.padding(  
                    vertical = 8.dp  
                )  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(14.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            RedAccent  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        10.dp  
                    )  
            )  

            Text(  
                text =  
                    "SOLICITAÇÕES DE PIN",  

                fontSize =  
                    22.sp,  

                fontWeight =  
                    FontWeight.Bold,  

                color =  
                    TextWhite  
            )  
        }  
    }  

    item {  

        Card(  
            modifier =  
                Modifier.fillMaxWidth(),  

            colors =  
                CardDefaults.cardColors(  
                    containerColor =  
                        CardBlack  
                ),  

            border =  
                BorderStroke(  
                    1.dp,  
                    BorderDark  
                ),  

            shape =  
                RoundedCornerShape(  
                    10.dp  
                )  
        ) {  

            Column(  
                modifier =  
                    Modifier.padding(  
                        18.dp  
                    ),  

                verticalArrangement =  
                    Arrangement.spacedBy(  
                        8.dp  
                    )  
            ) {  

                Row(  
                    verticalAlignment =  
                        Alignment.CenterVertically  
                ) {  

                    Box(  
                        modifier =  
                            Modifier  
                                .size(10.dp)  
                                .clip(  
                                    CircleShape  
                                )  
                                .background(  
                                    if (  
                                        pinRequests  
                                            .isNotEmpty()  
                                    ) {  
                                        GreenLed  
                                    } else {  
                                        RedLed  
                                    }  
                                )  
                    )  

                    Spacer(  
                        modifier =  
                            Modifier.width(  
                                8.dp  
                            )  
                    )  

                    Text(  
                        text =  
                            if (  
                                pinRequests  
                                    .isNotEmpty()  
                            ) {  
                                "FILA ATIVA"  
                            } else {  
                                "AGUARDANDO SOLICITAÇÕES"  
                            },  

                        fontSize =  
                            16.sp,  

                        fontWeight =  
                            FontWeight.Bold,  

                        color =  
                            if (  
                                pinRequests  
                                    .isNotEmpty()  
                            ) {  
                                GreenLed  
                            } else {  
                                TextMuted  
                            }  
                    )  
                }  

                Text(  
                    text =  
                        "Solicitações pendentes: ${pinRequests.size}",  

                    color =  
                        TextWhite,  

                    fontSize =  
                        15.sp,  

                    fontWeight =  
                        FontWeight.SemiBold  
                )  

                Text(  
                    text =  
                        "Validade de cada chave: 10 minutos",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        13.sp  
                )  

                Text(  
                    text =  
                        "Quando o usuário clica em 'SOLICITAR PIN' no PS4, a chave aparece abaixo.",  

                    color =  
                        TextMuted,  

                    fontSize =  
                        12.sp,  

                    lineHeight =  
                        16.sp  
                )  
            }  
        }  
    }  

    if (  
        pinRequests.isEmpty()  
    ) {  

        item {  

            Card(  
                modifier =  
                    Modifier.fillMaxWidth(),  

                colors =  
                    CardDefaults.cardColors(  
                        containerColor =  
                            CardBlack  
                    ),  

                border =  
                    BorderStroke(  
                        1.dp,  
                        BorderDark  
                    ),  

                shape =  
                    RoundedCornerShape(  
                        10.dp  
                    )  
            ) {  

                Text(  
                    text =  
                        "Nenhuma solicitação no momento...",  

                    modifier =  
                        Modifier.padding(  
                            20.dp  
                        ),  

                    color =  
                        TextMuted,  

                    fontSize =  
                        15.sp  
                )  
            }  
        }  

    } else {  

        items(  
            items =  
                pinRequests,  

            key = {  
                it.id  
            }  
        ) { req ->  

            val elapsed =  
                System.currentTimeMillis() -  
                    req.createdAt  

            val remaining =  
                (  
                    (  
                        HttpServer  
                            .PIN_TIMEOUT_MS -  
                            elapsed  
                    ) / 1000L  
                ).coerceAtLeast(  
                    0L  
                )  

            Card(  
                modifier =  
                    Modifier.fillMaxWidth(),  

                colors =  
                    CardDefaults.cardColors(  
                        containerColor =  
                            CardBlack  
                    ),  

                border =  
                    BorderStroke(  
                        1.dp,  

                        if (  
                            req.isExpired  
                        ) {  
                            BorderDark  
                        } else {  
                            Color(  
                                0xFF331114  
                            )  
                        }  
                    ),  

                shape =  
                    RoundedCornerShape(  
                        10.dp  
                    )  
            ) {  

                Column(  
                    modifier =  
                        Modifier.padding(  
                            18.dp  
                        ),  

                    verticalArrangement =  
                        Arrangement.spacedBy(  
                            10.dp  
                        )  
                ) {  

                    Row(  
                        modifier =  
                            Modifier.fillMaxWidth(),  

                        horizontalArrangement =  
                            Arrangement.SpaceBetween,  

                        verticalAlignment =  
                            Alignment.CenterVertically  
                    ) {  

                        Text(  
                            text =  
                                req.gameTitle,  

                            fontSize =  
                                17.sp,  

                            fontWeight =  
                                FontWeight.Bold,  

                            color =  
                                TextWhite,  

                            modifier =  
                                Modifier.weight(  
                                    1f  
                                )  
                        )  

                        Row(  
                            verticalAlignment =  
                                Alignment.CenterVertically  
                        ) {  

                            Box(  
                                modifier =  
                                    Modifier  
                                        .size(8.dp)  
                                        .clip(  
                                            CircleShape  
                                        )  
                                        .background(  
                                            if (  
                                                req.isExpired  
                                            ) {  
                                                RedLed  
                                            } else {  
                                                GreenLed  
                                            }  
                                        )  
                            )  

                            Spacer(  
                                modifier =  
                                    Modifier.width(  
                                        6.dp  
                                    )  
                            )  

                            Text(  
                                text =  
                                    if (  
                                        req.isExpired  
                                    ) {  
                                        "EXPIRADO"  
                                    } else {  
                                        "${remaining / 60}m ${remaining % 60}s"  
                                    },  

                                color =  
                                    if (  
                                        req.isExpired  
                                    ) {  
                                        RedLed  
                                    } else {  
                                        Color(  
                                            0xFFFF9F0A  
                                        )  
                                    },  

                                fontSize =  
                                    12.sp,  

                                fontWeight =  
                                    FontWeight.Bold  
                            )  
                        }  
                    }  

                    Text(  
                        text =  
                            "Código / CUSA: ${req.gameKey}",  

                        fontSize =  
                            13.sp,  

                        color =  
                            Color(  
                                0xFF64B5F6  
                            ),  

                        fontWeight =  
                            FontWeight.SemiBold  
                    )  

                    Card(  
                        modifier =  
                            Modifier.fillMaxWidth(),  

                        colors =  
                            CardDefaults.cardColors(  
                                containerColor =  
                                    Color(  
                                        0xFF0F0F0F  
                                    )  
                            ),  

                        border =  
                            BorderStroke(  
                                1.dp,  
                                BorderDark  
                            ),  

                        shape =  
                            RoundedCornerShape(  
                                8.dp  
                            )  
                    ) {  

                        Row(  
                            modifier =  
                                Modifier  
                                    .fillMaxWidth()  
                                    .padding(  
                                        horizontal = 14.dp,  
                                        vertical = 10.dp  
                                    ),  

                            horizontalArrangement =  
                                Arrangement.SpaceBetween,  

                            verticalAlignment =  
                                Alignment.CenterVertically  
                        ) {  

                            Text(  
                                text =  
                                    "PIN:",  

                                fontSize =  
                                    14.sp,  

                                fontWeight =  
                                    FontWeight.Bold,  

                                color =  
                                    TextMuted  
                            )  

                            Text(  
                                text =  
                                    req.pin,  

                                fontSize =  
                                    24.sp,  

                                fontWeight =  
                                    FontWeight.Black,  

                                letterSpacing =  
                                    2.sp,  

                                color =  
                                    if (  
                                        req.isExpired  
                                    ) {  
                                        RedLed  
                                    } else {  
                                        GreenLed  
                                    }  
                            )  
                        }  
                    }  

                    Spacer(  
                        modifier =  
                            Modifier.height(  
                                2.dp  
                            )  
                    )  

                    Row(  
                        modifier =  
                            Modifier.fillMaxWidth(),  

                        horizontalArrangement =  
                            Arrangement.spacedBy(  
                                8.dp  
                            )  
                    ) {  

                        Button(  
                            onClick = {  

                                val sendIntent =  
                                    Intent(  
                                        Intent.ACTION_SEND  
                                    ).apply {  

                                        type =  
                                            "text/plain"  

                                        putExtra(  
                                            Intent.EXTRA_TEXT,  

                                            "Seu PIN de download para o jogo *${req.gameTitle}* (${req.gameKey}) na GTSTORE é: *${req.pin}*\n\n⚠️ Válido por 10 minutos."  
                                        )  
                                    }  

                                context.startActivity(  
                                    Intent.createChooser(  
                                        sendIntent,  
                                        "Enviar PIN no WhatsApp"  
                                    )  
                                )  
                            },  

                            modifier =  
                                Modifier  
                                    .weight(  
                                        1.3f  
                                    )  
                                    .height(  
                                        46.dp  
                                    ),  

                            colors =  
                                ButtonDefaults.buttonColors(  
                                    containerColor =  
                                        RedAccent  
                                ),  

                            shape =  
                                RoundedCornerShape(  
                                    8.dp  
                                )  
                        ) {  

                            Text(  
                                text =  
                                    "WHATSAPP",  

                                fontWeight =  
                                    FontWeight.Bold,  

                                fontSize =  
                                    12.sp  
                            )  
                        }  

                        Button(  
                            onClick = {  

                                val clipboard =  
                                    context  
                                        .getSystemService(  
                                            Context.CLIPBOARD_SERVICE  
                                        ) as ClipboardManager  

                                clipboard  
                                    .setPrimaryClip(  
                                        ClipData  
                                            .newPlainText(  
                                                "PIN PS4",  
                                                req.pin  
                                            )  
                                    )  

                                Toast.makeText(  
                                    context,  
                                    "PIN copiado!",  
                                    Toast.LENGTH_SHORT  
                                ).show()  
                            },  

                            modifier =  
                                Modifier  
                                    .weight(  
                                        1f  
                                    )  
                                    .height(  
                                        46.dp  
                                    ),  

                            colors =  
                                ButtonDefaults.buttonColors(  
                                    containerColor =  
                                        Color(  
                                            0xFF222222  
                                        )  
                                ),  

                            shape =  
                                RoundedCornerShape(  
                                    8.dp  
                                )  
                        ) {  

                            Text(  
                                text =  
                                    "COPIAR",  

                                fontWeight =  
                                    FontWeight.Bold,  

                                fontSize =  
                                    12.sp  
                            )  
                        }  
                    }  
                }  
            }  
        }  
    }  

    item {  

        Button(  
            onClick =  
                onBack,  

            modifier =  
                Modifier  
                    .fillMaxWidth()  
                    .height(  
                        48.dp  
                    ),  

            colors =  
                ButtonDefaults.buttonColors(  
                    containerColor =  
                        Color(  
                            0xFF141414  
                        )  
                ),  

            shape =  
                RoundedCornerShape(  
                    8.dp  
                )  
        ) {  

            Text(  
                "VOLTAR",  
                color =  
                    TextWhite  
            )  
        }  

        Spacer(  
            modifier =  
                Modifier.height(  
                    16.dp  
                )  
        )  
    }  
}  



}


// ============================================================

// COMPONENTES DE SUPORTE

// ============================================================


@Composable

fun StatusCardLed(

title: String,

status: String,

isOnline: Boolean

) {


Card(  
    modifier =  
        Modifier.fillMaxWidth(),  

    colors =  
        CardDefaults.cardColors(  
            containerColor =  
                CardBlack  
        ),  

    border =  
        BorderStroke(  
            1.dp,  
            BorderDark  
        ),  

    shape =  
        RoundedCornerShape(  
            10.dp  
        )  
) {  

    Row(  
        modifier =  
            Modifier  
                .fillMaxWidth()  
                .padding(  
                    horizontal = 16.dp,  
                    vertical = 14.dp  
                ),  

        horizontalArrangement =  
            Arrangement.SpaceBetween,  

        verticalAlignment =  
            Alignment.CenterVertically  
    ) {  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(8.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            RedAccent  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        8.dp  
                    )  
            )  

            Text(  
                text =  
                    title,  

                fontWeight =  
                    FontWeight.Bold,  

                fontSize =  
                    15.sp,  

                color =  
                    TextWhite  
            )  
        }  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(10.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            if (  
                                isOnline  
                            ) {  
                                GreenLed  
                            } else {  
                                RedLed  
                            }  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        8.dp  
                    )  
            )  

            Text(  
                text =  
                    status,  

                fontWeight =  
                    FontWeight.Bold,  

                fontSize =  
                    14.sp,  

                color =  
                    if (  
                        isOnline  
                    ) {  
                        GreenLed  
                    } else {  
                        RedLed  
                    }  
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
    modifier =  
        Modifier.fillMaxWidth(),  

    colors =  
        CardDefaults.cardColors(  
            containerColor =  
                CardBlack  
        ),  

    border =  
        BorderStroke(  
            1.dp,  
            BorderDark  
        ),  

    shape =  
        RoundedCornerShape(  
            10.dp  
        )  
) {  

    Row(  
        modifier =  
            Modifier  
                .fillMaxWidth()  
                .padding(  
                    horizontal = 16.dp,  
                    vertical = 14.dp  
                ),  

        horizontalArrangement =  
            Arrangement.SpaceBetween,  

        verticalAlignment =  
            Alignment.CenterVertically  
    ) {  

        Row(  
            verticalAlignment =  
                Alignment.CenterVertically  
        ) {  

            Box(  
                modifier =  
                    Modifier  
                        .size(8.dp)  
                        .clip(  
                            CircleShape  
                        )  
                        .background(  
                            RedAccent  
                        )  
            )  

            Spacer(  
                modifier =  
                    Modifier.width(  
                        8.dp  
                    )  
            )  

            Text(  
                text =  
                    title,  

                fontWeight =  
                    FontWeight.Bold,  

                fontSize =  
                    15.sp,  

                color =  
                    TextWhite  
            )  
        }  

        Text(  
            text =  
                status,  

            fontWeight =  
                FontWeight.SemiBold,  

            fontSize =  
                14.sp,  

            color =  
                statusColor  
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
    onClick =  
        onClick,  

    modifier =  
        Modifier  
            .fillMaxWidth()  
            .height(  
                52.dp  
            ),  

    colors =  
        ButtonDefaults.buttonColors(  
            containerColor =  
                RedAccent,  

            contentColor =  
                TextWhite  
        ),  

    shape =  
        RoundedCornerShape(  
            10.dp  
        )  
) {  

    Text(  
        text =  
            text,  

        fontSize =  
            16.sp,  

        fontWeight =  
            FontWeight.ExtraBold,  

        letterSpacing =  
            1.sp  
    )  
}  



}


