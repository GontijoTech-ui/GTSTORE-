package com.gtstore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PinRequest(
    val id: Long = System.currentTimeMillis(),
    val gameTitle: String = "Jogo",
    val gameKey: String = "ALL",
    val pin: String = "",
    val clientIp: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean
        get() = (System.currentTimeMillis() - createdAt) > HttpServer.PIN_TIMEOUT_MS
}

// Compatibilidade de tipo
typealias PinEntry = PinRequest

class HttpServer(
    private val context: Context,
    private val port: Int = 8080
) {

    companion object {
        const val PIN_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutos de validade
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_HEADER_SIZE = 64 * 1024
        private const val MAX_POST_SIZE = 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val PAYLOAD_DIR = "payloads"
        private const val MAX_LOG_LINES = 300
        private const val PKG_CACHE_MS = 15_000L
        private const val ZERO_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"
        private const val MASTER_PIN = "888888"
    }

    private var serverSocket: ServerSocket? = null

    @Volatile
    var running = false
        private set

    private val executor = Executors.newCachedThreadPool()
    private val activeConnections = AtomicInteger(0)
    private val logLines = Collections.synchronizedList(ArrayList<String>())

    private val pinRequests = Collections.synchronizedList(ArrayList<PinRequest>())

    fun getPinRequests(): List<PinRequest> {
        synchronized(pinRequests) {
            pinRequests.removeAll { (System.currentTimeMillis() - it.createdAt) > 60 * 60 * 1000L }
            return ArrayList(pinRequests)
        }
    }

    // Compatibilidade com código anterior
    fun getActivePinsList(): List<PinRequest> = getPinRequests()

    fun createPinForGame(gameTitle: String, gameKey: String, clientIp: String = ""): PinRequest {
        val pin = (100000..999999).random().toString()
        val req = PinRequest(
            id = System.currentTimeMillis(),
            gameTitle = gameTitle,
            gameKey = gameKey,
            pin = pin,
            clientIp = clientIp,
            createdAt = System.currentTimeMillis()
        )
        synchronized(pinRequests) {
            pinRequests.add(0, req)
            while (pinRequests.size > 80) pinRequests.removeAt(pinRequests.size - 1)
        }
        dbg("PIN gerado para $gameTitle ($gameKey): $pin")
        return req
    }

    fun generateAdminPin(): String = createPinForGame("Acesso Geral", "ALL").pin

    private fun dbg(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val line = "$time $msg"
        android.util.Log.d("GTStore", line)
        synchronized(logLines) {
            logLines.add(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
    }

    val localAddress: String
        get() = getWifiIpv4Address()?.hostAddress ?: "0.0.0.0"

    data class ServerStatus(
        val running: Boolean,
        val port: Int,
        val localAddress: String,
        val url: String,
        val activeConnections: Int
    )

    data class PackageInfo(
        val id: Int,
        val name: String,
        val fileName: String,
        val size: Long,
        val modified: Long,
        val type: String,
        val uri: Uri
    )

    fun start() {
        if (running) return

        executor.execute {
            try {
                val address = getWifiIpv4Address()
                serverSocket = if (address != null) {
                    ServerSocket(port, 100, address)
                } else {
                    ServerSocket(port, 100)
                }

                serverSocket?.reuseAddress = true
                running = true
                dbg("servidor iniciado em ${address?.hostAddress ?: "0.0.0.0"}:$port")

                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(socket) }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            } catch (e: Exception) {
                dbg("falha ao iniciar servidor: $e")
                running = false
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    fun isRunning(): Boolean = running

    fun getStatus(): ServerStatus {
        val address = localAddress
        return ServerStatus(
            running = running,
            port = port,
            localAddress = address,
            url = "http://$address:$port",
            activeConnections = activeConnections.get()
        )
    }

    private fun handleClient(socket: Socket) {
        activeConnections.incrementAndGet()
        val clientIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""

        socket.use { client ->
            var target = ""

            try {
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.tcpNoDelay = true

                val input = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
                val output = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)

                val requestLine = input.readLine() ?: return
                if (requestLine.length > MAX_HEADER_SIZE) {
                    sendError(output, 431, "Header Too Large")
                    return
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendError(output, 400, "Bad Request")
                    return
                }

                val method = parts[0].uppercase()
                target = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                    }
                }

                if (!target.startsWith("/api/log")) {
                    dbg("$clientIp $method $target")
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_POST_SIZE) {
                    sendError(output, 413, "Entity Too Large")
                    return
                }

                val body = if (method == "POST" && contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = input.read(chars, off, contentLength - off)
                        if (n <= 0) break
                        off += n
                    }
                    String(chars, 0, off).toByteArray(StandardCharsets.ISO_8859_1)
                } else {
                    ByteArray(0)
                }

                when (method) {
                    "GET" -> handleRequest(target, headers, output, headOnly = false, clientIp)
                    "HEAD" -> handleRequest(target, headers, output, headOnly = true, clientIp)
                    "POST" -> handlePostRequest(target, headers, body, output, clientIp)
                    "OPTIONS" -> sendOptions(output)
                    else -> sendError(output, 405, "Method Not Allowed")
                }
            } catch (_: SocketException) {
            } catch (e: Exception) {
                dbg("erro em $target: $e")
                try {
                    val out = client.getOutputStream()
                    if (target.startsWith("/api/")) sendJsonError(out, 500, "Erro interno.")
                    else sendError(out, 500, "Internal Server Error")
                } catch (_: Exception) {
                }
            } finally {
                activeConnections.decrementAndGet()
            }
        }
    }

    private fun handlePostRequest(
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
        output: OutputStream,
        clientIp: String
    ) {
        val uri = Uri.parse(target)
        when (uri.path ?: "/") {
            "/api/install-dpi" -> handleDirectInstallDpi(body, headers, output, clientIp)
            "/api/request-pin" -> handlePinRequest(body, output, clientIp)
            "/api/verify-pin" -> handleVerifyPin(body, output)
            else -> sendJsonError(output, 404, "Endpoint não encontrado")
        }
    }

    private fun handlePinRequest(body: ByteArray, output: OutputStream, clientIp: String) {
        try {
            val json = JSONObject(String(body, StandardCharsets.UTF_8))
            val title = json.optString("title", "Jogo")
            val gameKey = json.optString("gameKey", "")

            if (gameKey.isBlank()) {
                sendJsonError(output, 400, "Código do jogo inválido.")
                return
            }

            val req = createPinForGame(title, gameKey, clientIp)
            sendJson(output, 200, JSONObject().put("success", true).put("requestId", req.id))
        } catch (_: Exception) {
            sendJsonError(output, 400, "Dados inválidos.")
        }
    }

    private fun handleVerifyPin(body: ByteArray, output: OutputStream) {
        try {
            val json = JSONObject(String(body, StandardCharsets.UTF_8))
            val pin = json.optString("pin").trim()
            val gameKey = json.optString("gameKey").trim()

            if (pin == MASTER_PIN) {
                sendJson(output, 200, JSONObject().put("valid", true))
                return
            }

            val matching = getPinRequests().firstOrNull { it.pin == pin }
            if (matching == null) {
                sendJson(output, 200, JSONObject().put("valid", false).put("message", "PIN incorreto ou não encontrado."))
                return
            }

            if (matching.isExpired) {
                sendJson(output, 200, JSONObject().put("valid", false).put("message", "Este PIN expirou! O prazo de 10 minutos encerrou."))
                return
            }

            if (!matching.gameKey.equals("ALL", ignoreCase = true) &&
                !matching.gameKey.equals(gameKey, ignoreCase = true)) {
                sendJson(
                    output,
                    200,
                    JSONObject().put("valid", false).put("message", "Este PIN é exclusivo para o jogo: ${matching.gameTitle}")
                )
                return
            }

            sendJson(output, 200, JSONObject().put("valid", true))
        } catch (_: Exception) {
            sendJsonError(output, 400, "Erro ao validar PIN.")
        }
    }

    private fun handleDirectInstallDpi(
        body: ByteArray,
        headers: Map<String, String>,
        output: OutputStream,
        clientIp: String
    ) {
        try {
            val json = if (body.isNotEmpty()) JSONObject(String(body, StandardCharsets.UTF_8)) else JSONObject()
            var ps4Ip = json.optString("ip").trim()
            if (!isValidIp(ps4Ip)) ps4Ip = clientIp

            val pkgId = json.optInt("pkgId", -1)
            if (!isValidIp(ps4Ip) || pkgId == -1) {
                sendJsonError(output, 400, "IP do PS4 ou ID inválido.")
                return
            }

            val packageInfo = getPackages().firstOrNull { it.id == pkgId }
            if (packageInfo == null) {
                sendJsonError(output, 404, "Pacote não encontrado.")
                return
            }

            val meta = metaFor(packageInfo)
            if (meta == null) {
                sendJsonError(output, 422, "Não foi possível ler metadados do PKG.")
                return
            }

            val payloadTemplate = loadPayload("payload.bin") ?: loadPayload("direct-installer.bin")
            if (payloadTemplate == null) {
                sendJsonError(output, 500, "Payload DPI ausente.")
                return
            }

            val payload = payloadTemplate.copyOf()
            val off = indexOf(payload, byteArrayOf(0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte(), 0xB4.toByte()))
            if (off < 0) {
                sendJsonError(output, 500, "Payload incompatível.")
                return
            }

            val localIp = requestHost(headers)
            if (localIp == "0.0.0.0" || localIp.isEmpty()) {
                sendJsonError(output, 500, "IP local indisponível.")
                return
            }

            val manifestUrl = "http://$localIp:$port/json/${packageInfo.id}.json"
            val localAddr = java.net.InetAddress.getByName(localIp)

            try {
                ServerSocket(0, 5, localAddr).use { tempServer ->
                    tempServer.soTimeout = 15_000
                    val callbackPort = tempServer.localPort

                    localAddr.address.copyInto(payload, off)
                    payload[off + 4] = (callbackPort ushr 8).toByte()
                    payload[off + 5] = callbackPort.toByte()

                    val binResult = sendPayloadToBinLoader(ps4Ip, payload)
                    if (!binResult.success) {
                        sendJsonError(output, 502, "Falha ao conectar no BinLoader (9090).")
                        return
                    }

                    try {
                        tempServer.accept().use { ps4Client ->
                            ps4Client.getOutputStream().apply {
                                write(buildDpiInfo(manifestUrl, packageInfo, meta))
                                flush()
                            }
                        }
                        dbg("DPI: instalação iniciada para ${packageInfo.fileName}")
                        sendJson(output, 200, JSONObject().put("success", true).put("message", "Instalação iniciada!"))
                    } catch (_: Exception) {
                        sendJsonError(output, 504, "Tempo esgotado aguardando o PS4.")
                    }
                }
            } catch (_: Exception) {
                sendJsonError(output, 500, "Falha na porta local.")
            }
        } catch (_: Exception) {
            sendJsonError(output, 400, "Erro na solicitação.")
        }
    }

    private fun buildDpiInfo(url: String, info: PackageInfo, meta: PkgMetaReader.Meta): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun str(s: String) {
            val b = s.toByteArray(StandardCharsets.UTF_8)
            i32(b.size)
            out.write(b)
        }

        i32(1)
        str(url)
        str(meta.title)
        str(meta.contentId)
        str(meta.bgftType)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(info.size).array())

        val icon = meta.icon
        if (icon == null || icon.isEmpty()) i32(0) else {
            i32(icon.size)
            out.write(icon)
        }
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
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

    private fun requestHost(headers: Map<String, String>): String {
        val host = headers["host"]?.substringBefore(':')?.trim()
        return if (!host.isNullOrEmpty() && isValidIp(host)) host else localAddress
    }

    private data class PayloadResult(val success: Boolean, val error: String? = null)

    private fun sendPayloadToBinLoader(ip: String, payload: ByteArray): PayloadResult {
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
                } catch (_: Exception) {
                }
            }
            PayloadResult(true)
        } catch (e: Exception) {
            PayloadResult(false, e.message)
        }
    }

    private fun loadPayload(payloadName: String): ByteArray? {
        val targets = listOf("$PAYLOAD_DIR/$payloadName", payloadName)
        for (target in targets) {
            try {
                context.assets.open(target).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    return output.toByteArray()
                }
            } catch (_: Exception) {
            }
        }
        return null
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

    private fun handleRequest(
        target: String,
        headers: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        clientIp: String
    ) {
        val uri = Uri.parse(target)
        val path = uri.path ?: "/"

        when {
            path == "/" || path == "/ps4" -> sendHomePage(output, headOnly)
            path == "/logo.jpg" -> sendAssetFile("logo.jpg", "image/jpeg", output, headOnly)
            path == "/qr.png" -> sendAssetFile("qr.png", "image/png", output, headOnly)
            path == "/api/status" -> sendStatusJson(output, headOnly, clientIp)
            path == "/api/packages" -> sendPackagesJson(output, headOnly)
            path.startsWith("/api/package-icon/") -> {
                val id = path.removePrefix("/api/package-icon/").split("/").firstOrNull()?.toIntOrNull()
                if (id != null) sendPackageIcon(id, output, headOnly) else sendError(output, 400, "ID inválido")
            }
            path == "/api/log" -> {
                val text = synchronized(logLines) { logLines.joinToString("\n") }.ifEmpty { "(sem logs)" }
                sendResponse(output, 200, "OK", "text/plain; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8), headOnly)
            }
            path.startsWith("/json/") -> {
                val id = path.removePrefix("/json/").removeSuffix(".json").toIntOrNull()
                if (id != null) sendManifestJson(id, headers, output, headOnly) else sendError(output, 404, "Not Found")
            }
            path == "/download" || path == "/pkg" -> {
                val id = uri.getQueryParameter("id")?.toIntOrNull()
                if (id == null) sendError(output, 400, "ID inválido") else servePackage(id, headers, output, headOnly)
            }
            path.startsWith("/pkg/") -> {
                val id = path.removePrefix("/pkg/").split("/").firstOrNull()?.toIntOrNull()
                if (id == null) sendError(output, 400, "ID inválido") else servePackage(id, headers, output, headOnly)
            }
            path == "/favicon.ico" -> sendResponse(output, 204, "No Content", "image/x-icon", ByteArray(0), headOnly)
            else -> sendError(output, 404, "Not Found")
        }
    }

    private fun sendAssetFile(fileName: String, mimeType: String, output: OutputStream, headOnly: Boolean) {
        try {
            context.assets.open(fileName).use { input ->
                sendResponse(output, 200, "OK", mimeType, input.readBytes(), headOnly)
            }
        } catch (_: Exception) {
            sendError(output, 404, "$fileName não encontrado na pasta assets.")
        }
    }

    private fun sendPackageIcon(packageId: Int, output: OutputStream, headOnly: Boolean) {
        val packageInfo = getPackages().firstOrNull { it.id == packageId } ?: return sendError(output, 404, "Not Found")
        val meta = metaFor(packageInfo)
        val icon = meta?.icon ?: return sendError(output, 404, "Ícone não encontrado")
        sendResponse(output, 200, "OK", "image/png", icon, headOnly)
    }

    private fun sendManifestJson(packageId: Int, headers: Map<String, String>, output: OutputStream, headOnly: Boolean) {
        val packageInfo = getPackages().firstOrNull { it.id == packageId } ?: return sendError(output, 404, "Not Found")
        val digest = metaFor(packageInfo)?.digest ?: ZERO_DIGEST
        val fileUrl = "http://${requestHost(headers)}:$port/pkg/${packageInfo.id}"

        val json = """
            {
              "originalFileSize":${packageInfo.size},
              "packageDigest":"$digest",
              "numberOfSplitFiles":1,
              "pieces":[{"url":"$fileUrl","fileOffset":0,"fileSize":${packageInfo.size},"hashValue":"0000000000000000000000000000000000000000"}]
            }
        """.trimIndent()
        sendResponse(output, 200, "OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8), headOnly)
    }

    private fun servePackage(packageId: Int, headers: Map<String, String>, output: OutputStream, headOnly: Boolean) {
        val packageInfo = getPackages().firstOrNull { it.id == packageId } ?: return sendError(output, 404, "Not Found")
        val totalSize = packageInfo.size
        var start = 0L
        var end = if (totalSize > 0L) totalSize - 1L else 0L
        var partial = false

        val rangeHeader = headers["range"]
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            try {
                val range = rangeHeader.removePrefix("bytes=").trim()
                if (range.endsWith("-")) {
                    start = range.removeSuffix("-").toLong()
                    end = totalSize - 1L
                } else if (range.contains("-")) {
                    val p = range.split("-")
                    start = p[0].toLong()
                    end = p[1].toLong().coerceAtMost(totalSize - 1L)
                }
                partial = true
            } catch (_: Exception) {
            }
        }

        val contentLength = if (totalSize == 0L) 0L else end - start + 1L
        val statusCode = if (partial) 206 else 200
        val statusText = if (partial) "Partial Content" else "OK"

        val responseHeaders = LinkedHashMap<String, String>()
        responseHeaders["Accept-Ranges"] = "bytes"
        responseHeaders["Content-Length"] = contentLength.toString()
        responseHeaders["Content-Type"] = "application/octet-stream"
        responseHeaders["Content-Disposition"] = "attachment; filename=\"${sanitizeFileName(packageInfo.fileName)}\""
        if (partial) responseHeaders["Content-Range"] = "bytes $start-$end/$totalSize"

        writeHeaders(output, statusCode, statusText, responseHeaders)
        if (headOnly || contentLength <= 0L) {
            output.flush()
            return
        }

        try {
            context.contentResolver.openFileDescriptor(packageInfo.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    fis.channel.position(start)
                    streamRange(fis, output, contentLength)
                }
            }
        } catch (_: Exception) {
        }
        try {
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun streamRange(input: InputStream, output: OutputStream, bytesToSend: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytesToSend
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, requested)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return bytesToSend - remaining
    }

    @Volatile
    private var pkgCache: List<PackageInfo> = emptyList()
    @Volatile
    private var pkgCacheAt = 0L
    private val metaCache = java.util.concurrent.ConcurrentHashMap<String, PkgMetaReader.Meta>()

    private fun getPackages(force: Boolean = false): List<PackageInfo> {
        if (!force && pkgCache.isNotEmpty() && System.currentTimeMillis() - pkgCacheAt < PKG_CACHE_MS) return pkgCache
        synchronized(this) {
            if (!force && pkgCache.isNotEmpty() && System.currentTimeMillis() - pkgCacheAt < PKG_CACHE_MS) return pkgCache
            val fresh = scanPackages()
            pkgCache = fresh
            pkgCacheAt = System.currentTimeMillis()
            return fresh
        }
    }

    private fun metaFor(pkg: PackageInfo): PkgMetaReader.Meta? {
        val key = "${pkg.uri}|${pkg.size}|${pkg.modified}"
        metaCache[key]?.let { return it }
        val meta = PkgMetaReader.read(context, pkg.uri) ?: return null
        metaCache[key] = meta
        return meta
    }

    private fun scanPackages(): List<PackageInfo> {
        val preferences = context.getSharedPreferences("GTSTORE", Context.MODE_PRIVATE)
        val savedUri = preferences.getString("pkg_folder_uri", null)
            ?: context.getSharedPreferences("GTSTORE_PREFS", Context.MODE_PRIVATE).getString("pkg_folder_uri", null)
        if (savedUri.isNullOrBlank()) return emptyList()

        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(savedUri))
        } catch (_: Exception) {
            null
        }
        if (root == null || !root.exists()) return emptyList()

        val files = mutableListOf<DocumentFile>()
        scanDocumentFile(root, files)

        return files.filter { it.isFile && it.name?.lowercase()?.endsWith(".pkg") == true }
            .sortedBy { it.name?.lowercase() ?: "" }
            .mapIndexed { index, file ->
                val name = file.name ?: "package.pkg"
                PackageInfo(index + 1, name, name, file.length(), file.lastModified(), "PKG", file.uri)
            }
    }

    private fun scanDocumentFile(root: DocumentFile, result: MutableList<DocumentFile>) {
        val children = try {
            root.listFiles()
        } catch (_: Exception) {
            emptyArray()
        }
        for (child in children) {
            if (child.isDirectory) scanDocumentFile(child, result) else result.add(child)
        }
    }

    private fun sendPackagesJson(output: OutputStream, headOnly: Boolean) {
        val packages = getPackages(force = true)
        val array = JSONArray()

        for (pkg in packages) {
            val meta = metaFor(pkg)
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: pkg.name
            val contentId = meta?.contentId ?: ""
            val category = meta?.category ?: ""
            val catalogType = classifyCatalogType(category)

            array.put(
                JSONObject()
                    .put("id", pkg.id)
                    .put("title", title)
                    .put("file", pkg.fileName)
                    .put("size", pkg.size)
                    .put("catalogType", catalogType)
                    .put("contentId", contentId)
                    .put("iconUrl", if (meta?.icon?.isNotEmpty() == true) "/api/package-icon/${pkg.id}" else "")
            )
        }

        val body = JSONObject().put("count", packages.size).put("packages", array).toString().toByteArray(StandardCharsets.UTF_8)
        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body, headOnly)
    }

    private fun classifyCatalogType(category: String): String {
        return when (category.trim().lowercase()) {
            "gd" -> "GAME"
            "gp" -> "UPDATE"
            "ac" -> "DLC"
            else -> "OTHER"
        }
    }

    private fun sendStatusJson(output: OutputStream, headOnly: Boolean, clientIp: String) {
        val status = getStatus()
        val json = JSONObject()
            .put("online", status.running)
            .put("port", status.port)
            .put("localAddress", status.localAddress)
            .put("clientIp", clientIp)

        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        sendResponse(output, 200, "OK", "application/json; charset=utf-8", body, headOnly)
    }

    private fun sendHomePage(output: OutputStream, headOnly: Boolean) {
        try {
            context.assets.open("index.html").use { input ->
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", input.readBytes(), headOnly)
            }
        } catch (_: Exception) {
            sendError(output, 404, "index.html não encontrado.")
        }
    }

    private fun sendOptions(output: OutputStream) {
        writeHeaders(output, 204, "No Content", mapOf("Access-Control-Allow-Origin" to "*", "Content-Length" to "0"))
        output.flush()
    }

    private fun sendJson(output: OutputStream, statusCode: Int, json: JSONObject) {
        val body = json.toString().toByteArray(StandardCharsets.UTF_8)
        sendResponse(output, statusCode, "OK", "application/json; charset=utf-8", body, false)
    }

    private fun sendJsonError(output: OutputStream, statusCode: Int, message: String) {
        sendJson(output, statusCode, JSONObject().put("success", false).put("error", message))
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean
    ) {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = contentType
        headers["Content-Length"] = body.size.toString()
        headers["Access-Control-Allow-Origin"] = "*"
        writeHeaders(output, statusCode, statusText, headers)
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: OutputStream, statusCode: Int, statusText: String, headers: Map<String, String>) {
        val builder = StringBuilder().append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
        for (entry in headers) builder.append(entry.key).append(": ").append(entry.value).append("\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendError(output: OutputStream, statusCode: Int, message: String) {
        val body = "<h1>$statusCode</h1><p>$message</p>".toByteArray(StandardCharsets.UTF_8)
        sendResponse(output, statusCode, message, "text/html; charset=utf-8", body, false)
    }

    private fun getWifiIpv4Address(): Inet4Address? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String = name.replace("\"", "").replace("\r", "").replace("\n", "_")
}
