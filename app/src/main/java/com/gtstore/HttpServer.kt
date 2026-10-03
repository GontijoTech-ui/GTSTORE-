package com.gtstore

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.NetworkInterface

data class ServerStatusInfo(
    val running: Boolean,
    val port: Int,
    val localAddress: String,
    val activeConnections: Int
)

class HttpServer(
    private val context: Context,
    private val port: Int = 8080,
    private val catalogManager: CatalogManager = CatalogManager(context)
) : NanoHTTPD(port) {

    private var running = false
    private var activeConnectionsCount = 0

    fun isRunning(): Boolean = running

    override fun start() {
        super.start()
        running = true
    }

    override fun stop() {
        super.stop()
        running = false
    }

    fun getStatus(): ServerStatusInfo {
        return ServerStatusInfo(
            running = running,
            port = port,
            localAddress = getLocalIpAddress() ?: "127.0.0.1",
            activeConnections = activeConnectionsCount
        )
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(response)
            return response
        }

        activeConnectionsCount++
        val response = try {
            when {
                // STATUS DO SERVIDOR
                uri == "/api/status" && method == Method.GET -> handleStatus(session)

                // CATÁLOGO DE JOGOS
                uri == "/api/packages" && method == Method.GET -> handlePackages()

                // ROTA ORIGINAL EXATA PARA CARREGAR CAPAS DOS JOGOS
                uri.startsWith("/api/package-icon/") && method == Method.GET -> handlePackageIcon(uri)

                // COMPATIBILIDADE EXTRA CASO ACESSEM /covers/{id}
                uri.startsWith("/covers/") && method == Method.GET -> handlePackageIcon(uri)

                // SOLICITAÇÃO DO CARRINHO EM LOTE (PS4)
                uri == "/api/request-cart-access" && method == Method.POST -> handleRequestCartAccess(session)

                // CHECAGEM DE STATUS (POLLING A CADA 10s)
                uri == "/api/check-access" && method == Method.GET -> handleCheckAccess(session)

                // DOWNLOAD / REDIRECT DO JOGO
                (uri == "/download" || uri == "/pkg") && method == Method.GET -> handleDownloadPkg(session)

                // ARQUIVOS ESTÁTICOS (index.html, logo.jpg, qr.png)
                else -> handleStaticFiles(uri)
            }
        } catch (e: Exception) {
            AppLogger.log("[HttpServer] Erro interno: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Erro interno: ${e.message}"}"""
            )
        } finally {
            activeConnectionsCount = (activeConnectionsCount - 1).coerceAtLeast(0)
        }

        addCorsHeaders(response)
        return response
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range, Authorization")
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val json = JSONObject().apply {
            put("status", if (running) "online" else "offline")
            put("port", port)
            put("localAddress", getLocalIpAddress() ?: "")
            put("clientIp", session.remoteIpAddress ?: "")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // =========================================================================
    // CATÁLOGO (Usa a mesma convenção original: /api/package-icon/{id})
    // =========================================================================
    private fun handlePackages(): Response {
        val packages = catalogManager.getAll()
        val jsonArray = JSONArray()

        for (item in packages) {
            val icon = catalogManager.getIcon(item)
            val hasIcon = icon != null && icon.isNotEmpty()

            val obj = JSONObject().apply {
                put("id", item.catalogIndex)
                put("index", item.indexString)
                put("title", item.title)
                put("contentId", item.contentId)
                put("size", item.size)
                put("version", item.version)
                put("category", item.category)
                put("catalogType", item.type.ifBlank { "GAME" })
                put("url", item.url)
                // Aponta para o endpoint original garantido
                put("iconUrl", if (hasIcon) "/api/package-icon/${item.catalogIndex}" else "")
            }
            jsonArray.put(obj)
        }

        val json = JSONObject().apply {
            put("count", packages.size)
            put("packages", jsonArray)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    // =========================================================================
    // ENTREGA DO ÍCONE (LÓGICA ORIGINAL RESTAURADA: getByIndex + getIcon)
    // =========================================================================
    private fun handlePackageIcon(uri: String): Response {
        // Extrai o ID da URL (ex: "/api/package-icon/1" ou "/covers/1")
        val rawId = uri.substringAfterLast("/").substringBefore(".").substringBefore("?")
        val packageId = rawId.toIntOrNull()

        if (packageId != null) {
            val item = catalogManager.getByIndex(packageId)
            if (item != null) {
                val icon = catalogManager.getIcon(item)
                if (icon != null && icon.isNotEmpty()) {
                    val mime = if (icon.size > 8 && icon[0] == 0x89.toByte() && icon[1] == 0x50.toByte()) {
                        "image/png"
                    } else {
                        "image/jpeg"
                    }
                    val resp = newFixedLengthResponse(
                        Response.Status.OK,
                        mime,
                        ByteArrayInputStream(icon),
                        icon.size.toLong()
                    )
                    resp.addHeader("Cache-Control", "public, max-age=86400")
                    return resp
                }
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Ícone não encontrado")
    }

    // =========================================================================
    // CARRINHO E CONTROLE DE ACESSO (15 MINUTOS)
    // =========================================================================
    private fun handleRequestCartAccess(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: ""
        val json = JSONObject(postData)

        val consoleId = json.optString("consoleId").trim()
        val gamesJson = json.optJSONArray("games") ?: JSONArray()
        val clientIp = session.remoteIpAddress ?: ""

        if (consoleId.isNotBlank() && gamesJson.length() > 0) {
            val gamesList = mutableListOf<RequestedGame>()
            for (i in 0 until gamesJson.length()) {
                val g = gamesJson.getJSONObject(i)
                val key = g.optString("gameKey").trim()
                val title = g.optString("title").trim()
                if (key.isNotBlank()) {
                    gamesList.add(RequestedGame(gameKey = key, title = title.ifBlank { key }))
                }
            }

            if (gamesList.isNotEmpty()) {
                AccessManager.addBatchRequest(
                    AccessBatchRequest(
                        consoleId = consoleId,
                        games = gamesList,
                        clientIp = clientIp
                    )
                )
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"success":true}"""
                )
            }
        }

        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "application/json",
            """{"success":false,"error":"Dados inválidos"}"""
        )
    }

    private fun handleCheckAccess(session: IHTTPSession): Response {
        val params = session.parameters
        val consoleId = params["consoleId"]?.firstOrNull()?.trim() ?: ""
        val gameKeysParam = params["gameKeys"]?.firstOrNull()?.trim() ?: ""
        val singleKey = params["gameKey"]?.firstOrNull()?.trim() ?: ""

        val approved = when {
            consoleId.isNotBlank() && gameKeysParam.isNotBlank() -> {
                val keys = gameKeysParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
                AccessManager.areAllGamesApproved(consoleId, keys)
            }
            consoleId.isNotBlank() && singleKey.isNotBlank() -> {
                AccessManager.isAccessApproved(consoleId, singleKey)
            }
            else -> false
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"approved":$approved}"""
        )
    }

    private fun handleDownloadPkg(session: IHTTPSession): Response {
        val idStr = session.parameters["id"]?.firstOrNull() ?: ""
        val consoleId = session.parameters["consoleId"]?.firstOrNull() ?: ""
        val index = idStr.toIntOrNull() ?: -1

        val item = catalogManager.getByIndex(index)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Item não encontrado")

        val gameKey = item.contentId.substringBefore("_00-").ifBlank { item.title }
        if (consoleId.isNotBlank() && !AccessManager.isAccessApproved(consoleId, gameKey)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Acesso expirado (15 min).")
        }

        val directLink = item.url.trim()

        if (directLink.startsWith("http://", ignoreCase = true) || directLink.startsWith("https://", ignoreCase = true)) {
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", directLink)
            return response
        }

        val file = File(directLink)
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Arquivo local não existe")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            FileInputStream(file),
            file.length()
        )
    }

    // =========================================================================
    // ARQUIVOS ESTÁTICOS (assets/ raiz)
    // =========================================================================
    private fun handleStaticFiles(uri: String): Response {
        val cleanUri = uri.substringBefore("?").trim().removePrefix("/")
        val targetFile = if (cleanUri.isBlank() || cleanUri == "/") "index.html" else cleanUri

        val stream: InputStream = try {
            context.assets.open(targetFile)
        } catch (_: Exception) {
            try {
                context.assets.open("web/$targetFile")
            } catch (_: Exception) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "404 Not Found: $targetFile"
                )
            }
        }

        val mime = when {
            targetFile.endsWith(".html", ignoreCase = true) -> "text/html; charset=utf-8"
            targetFile.endsWith(".js", ignoreCase = true) -> "application/javascript"
            targetFile.endsWith(".css", ignoreCase = true) -> "text/css"
            targetFile.endsWith(".jpg", ignoreCase = true) || targetFile.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            targetFile.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> MIME_PLAINTEXT
        }

        return newChunkedResponse(Response.Status.OK, mime, stream)
    }
}
