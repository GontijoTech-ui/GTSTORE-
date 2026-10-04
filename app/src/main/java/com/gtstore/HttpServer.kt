package com.gtstore

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

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

    companion object {
        private const val PAYLOAD_DIR = "payloads"
        private const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    }

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

    private fun extractGameKey(item: CatalogItem): String {
        val cid = item.contentId.uppercase()
        val match = Regex("CUSA\\d+").find(cid)
        if (match != null) {
            return match.value
        }
        val prefix = cid.substringBefore("_00-")
        if (prefix.isNotBlank()) {
            return prefix
        }
        return item.title.trim().uppercase()
    }

    private fun getPublicBaseUrl(session: IHTTPSession): String {
        val hostHeader = session.headers["host"] ?: ""
        val forwardedProto = session.headers["x-forwarded-proto"] ?: "http"
        return if (hostHeader.isNotBlank()) {
            "$forwardedProto://$hostHeader"
        } else {
            val localIp = getLocalIpAddress() ?: "127.0.0.1"
            "http://$localIp:$port"
        }
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

                // ROTA DE ÍCONES
                uri.startsWith("/api/package-icon/") && method == Method.GET -> handlePackageIcon(uri)

                // MANIFEST JSON COM URL PÚBLICA (SUPORTE A CLOUDFLARE/REMOTO)
                (uri.startsWith("/json/") || uri.startsWith("/json-public/")) && method == Method.GET -> handleManifestJson(session, uri)

                // SOLICITAÇÃO DE PACOTE (CARRINHO)
                uri == "/api/request-cart-access" && method == Method.POST -> handleRequestCartAccess(session)

                // CHECAGEM DE APROVAÇÃO (POLLING)
                uri == "/api/check-access" && method == Method.GET -> handleCheckAccess(session)

                // ENVIO DE PAYLOAD / INSTALAÇÃO DIRETA DPI (PORTA 9090)
                uri == "/api/install-dpi" && method == Method.POST -> handleDirectInstallDpi(session)

                // DOWNLOAD / REDIRECIONAMENTO DE PKG
                (uri == "/download" || uri == "/pkg") && method == Method.GET -> handleDownloadPkg(session)

                // ARQUIVOS ESTÁTICOS (index.html, imagens, etc.)
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
            put("online", running)
            put("port", port)
            put("localAddress", getLocalIpAddress() ?: "")
            put("clientIp", session.remoteIpAddress ?: "")
            put("baseUrl", getPublicBaseUrl(session))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

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

    private fun handlePackageIcon(uri: String): Response {
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

    private fun handleManifestJson(session: IHTTPSession, uri: String): Response {
        val rawId = uri.removePrefix("/json-public/").removePrefix("/json/").removeSuffix(".json").substringBefore("?")
        val packageId = rawId.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "ID inválido")

        val item = catalogManager.getByIndex(packageId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Jogo não encontrado")

        if (item.url.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "URL do item ausente")
        }

        val baseUrl = getPublicBaseUrl(session)
        val downloadUrl = if (item.url.startsWith("http://", ignoreCase = true) || item.url.startsWith("https://", ignoreCase = true)) {
            item.url
        } else {
            "$baseUrl/download?id=${item.catalogIndex}"
        }

        val digest = if (item.digest.isNotBlank()) item.digest else ZERO_DIGEST
        val json = JSONObject().apply {
            put("originalFileSize", item.size)
            put("packageDigest", digest)
            put("numberOfSplitFiles", 1)
            put(
                "pieces",
                JSONArray().put(
                    JSONObject().apply {
                        put("url", downloadUrl)
                        put("fileOffset", 0)
                        put("fileSize", item.size)
                        put("hashValue", "0000000000000000000000000000000000000000")
                    }
                )
            )
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
    }

    private fun handleDirectInstallDpi(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: ""
        val json = if (postData.isNotBlank()) JSONObject(postData) else JSONObject()

        var ps4Ip = json.optString("ip").trim()
        if (!isValidIp(ps4Ip)) {
            ps4Ip = session.remoteIpAddress ?: ""
        }

        val pkgId = json.optInt("pkgId", -1)
        val consoleId = json.optString("consoleId").trim()

        if (!isValidIp(ps4Ip) || pkgId == -1) {
            return jsonError(400, "IP do PS4 ou ID inválido.")
        }

        val item = catalogManager.getByIndex(pkgId)
            ?: return jsonError(404, "Item do catálogo não encontrado.")

        if (item.url.isBlank()) {
            return jsonError(422, "O item do catálogo não possui URL.")
        }

        val gameKey = extractGameKey(item)
        if (consoleId.isNotBlank() && !AccessManager.isAccessApproved(consoleId, gameKey)) {
            AppLogger.log("[HttpServer] Bloqueado 403: Console $consoleId tentou $gameKey")
            return jsonError(403, "Acesso não autorizado ou expirado.")
        }

        val payloadTemplate = loadPayload("payload.bin") ?: loadPayload("direct-installer.bin")
            ?: return jsonError(500, "Arquivo payload.bin ausente na pasta assets.")

        val payload = payloadTemplate.copyOf()
        val off = indexOf(
            payload,
            byteArrayOf(0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte())
        )

        if (off < 0) {
            return jsonError(500, "Payload incompatível.")
        }

        val localIp = getLocalIpAddress()
            ?: return jsonError(500, "IP local do Android indisponível.")

        val manifestUrl = "${getPublicBaseUrl(session)}/json/${item.catalogIndex}.json"
        val localAddr = java.net.InetAddress.getByName(localIp)

        try {
            ServerSocket(0, 5, localAddr).use { tempServer ->
                tempServer.soTimeout = 15_000
                val callbackPort = tempServer.localPort

                localAddr.address.copyInto(payload, off)
                payload[off + 4] = (callbackPort ushr 8).toByte()
                payload[off + 5] = callbackPort.toByte()

                val binSuccess = sendPayloadToBinLoader(ps4Ip, payload)
                if (!binSuccess) {
                    return jsonError(502, "Falha ao conectar no BinLoader (9090) do PS4. O console está na mesma rede local?")
                }

                try {
                    tempServer.accept().use { ps4Client ->
                        ps4Client.getOutputStream().apply {
                            write(buildDpiInfo(manifestUrl, item))
                            flush()
                        }
                    }

                    AppLogger.log("[DPI] Instalação iniciada para ${item.title} ($gameKey) no PS4 ($ps4Ip)")
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"success":true,"message":"Instalação iniciada!"}"""
                    )
                } catch (_: Exception) {
                    return jsonError(504, "Tempo esgotado aguardando o PS4 responder ao callback.")
                }
            }
        } catch (e: Exception) {
            return jsonError(500, "Falha no servidor local: ${e.message}")
        }
    }

    private fun sendPayloadToBinLoader(ip: String, payload: ByteArray): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = 8000
                socket.connect(InetSocketAddress(ip, 9090), 5000)

                val out = socket.getOutputStream()
                out.write(payload)
                out.flush()
                try {
                    socket.shutdownOutput()
                } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            AppLogger.log("[DPI] Erro ao enviar payload (9090): ${e.message}")
            false
        }
    }

    private fun buildDpiInfo(url: String, item: CatalogItem): ByteArray {
        val out = ByteArrayOutputStream()

        fun i32(v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }

        fun i64(v: Long) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        }

        fun str(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            i32(b.size)
            out.write(b)
        }

        i32(1)
        str(url)
        str(item.title)
        str(item.contentId)

        val bgftType = "PS4" + item.category.uppercase()
        str(bgftType)
        i64(item.size)

        val icon = catalogManager.getIcon(item)
        if (icon == null || icon.isEmpty()) {
            i32(0)
        } else {
            i32(icon.size)
            out.write(icon)
        }

        return out.toByteArray()
    }

    private fun loadPayload(payloadName: String): ByteArray? {
        val targets = listOf("$PAYLOAD_DIR/$payloadName", payloadName)
        for (target in targets) {
            try {
                context.assets.open(target).use { input ->
                    return input.readBytes()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty()) return 0
        if (pattern.size > data.size) return -1
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun isValidIp(ip: String): Boolean {
        if (ip.isBlank()) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return try {
            parts.all { it.toInt() in 0..255 }
        } catch (_: Exception) {
            false
        }
    }

    private fun jsonError(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            422 -> Response.Status.BAD_REQUEST
            502 -> Response.Status.INTERNAL_ERROR
            504 -> Response.Status.INTERNAL_ERROR
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(
            status,
            "application/json",
            """{"success":false,"error":"$message"}"""
        )
    }

    private fun handleRequestCartAccess(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: ""
        val json = JSONObject(postData)

        val consoleId = json.optString("consoleId").trim()
        val gamesJson = json.optJSONArray("games") ?: JSONArray()
        var clientIp = json.optString("clientIp").trim()
        if (!isValidIp(clientIp)) {
            clientIp = session.remoteIpAddress ?: ""
        }

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

        val gameKey = extractGameKey(item)
        if (consoleId.isNotBlank() && !AccessManager.isAccessApproved(consoleId, gameKey)) {
            AppLogger.log("[HttpServer] Bloqueado 403 Download: Console $consoleId tentou $gameKey")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Acesso não autorizado ou expirado.")
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
