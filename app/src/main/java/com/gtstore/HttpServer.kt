package com.gtstore

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
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
                uri == "/api/status" && method == Method.GET -> handleStatus(session)
                uri == "/api/packages" && method == Method.GET -> handlePackages()
                uri == "/api/request-cart-access" && method == Method.POST -> handleRequestCartAccess(session)
                uri == "/api/check-access" && method == Method.GET -> handleCheckAccess(session)
                uri.startsWith("/download") && method == Method.GET -> handleDownloadPkg(session)
                uri.startsWith("/covers/") && method == Method.GET -> handleCoverImage(uri)
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

    private fun handlePackages(): Response {
        val items = catalogManager.getAll()
        val jsonArray = JSONArray()

        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.catalogIndex)
                put("title", item.title)
                put("contentId", item.contentId)
                put("size", 0L)
                put("catalogType", item.type.ifBlank { "GAME" })
                put("iconUrl", if (item.iconFile.isNotBlank()) "/covers/${item.iconFile}" else "")
            }
            jsonArray.put(obj)
        }

        val json = JSONObject().apply {
            put("packages", jsonArray)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

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

        val item = catalogManager.getAll().find { it.catalogIndex == index }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Item não encontrado")

        val gameKey = item.contentId.substringBefore("_00-").ifBlank { item.title }
        if (consoleId.isNotBlank() && !AccessManager.isAccessApproved(consoleId, gameKey)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Acesso expirado (15 min).")
        }

        if (item.directUrl.startsWith("http://", ignoreCase = true) || item.directUrl.startsWith("https://", ignoreCase = true)) {
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", item.directUrl)
            return response
        }

        val file = File(item.directUrl)
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

    private fun handleCoverImage(uri: String): Response {
        val fileName = uri.removePrefix("/covers/")
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(file), file.length())
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Capa não encontrada")
    }

    private fun handleStaticFiles(uri: String): Response {
        val cleanPath = if (uri == "/" || uri.isBlank()) "web/index.html" else "web" + uri
        return try {
            val stream: InputStream = context.assets.open(cleanPath)
            val mime = when {
                cleanPath.endsWith(".html") -> "text/html; charset=utf-8"
                cleanPath.endsWith(".js") -> "application/javascript"
                cleanPath.endsWith(".css") -> "text/css"
                cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") -> "image/jpeg"
                cleanPath.endsWith(".png") -> "image/png"
                else -> MIME_PLAINTEXT
            }
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }
    }
}
