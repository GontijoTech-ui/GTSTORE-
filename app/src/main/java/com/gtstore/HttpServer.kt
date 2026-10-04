package com.gtstore

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modelo de dados com o estado atual do servidor HTTP.
 * Utilizado por MainActivity e GTStoreService via getStatus().
 */
data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 8080,
    val localAddress: String = "127.0.0.1",
    val activeConnections: Int = 0
)

/**
 * Estados do ciclo de vida das solicitações de checkout.
 */
enum class OrderStatus {
    PENDING,
    APPROVED,
    REJECTED
}

/**
 * Representação em memória de um pedido de checkout.
 */
data class Order(
    val id: String,
    val items: List<String>,
    var targetPs4Ip: String = "",
    var status: OrderStatus = OrderStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gerenciador thread-safe em memória para sincronização entre PS4 e Admin.
 */
object OrderManager {
    private val orders = ConcurrentHashMap<String, Order>()

    fun createOrder(items: List<String>, targetIp: String): Order {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val order = Order(id = id, items = items, targetPs4Ip = targetIp)
        orders[id] = order
        return order
    }

    fun getOrder(id: String): Order? = orders[id]

    fun updateStatus(id: String, status: OrderStatus): Boolean {
        val order = orders[id] ?: return false
        order.status = status
        return true
    }

    fun listPendingOrders(): List<Order> {
        return orders.values.filter { it.status == OrderStatus.PENDING }.sortedByDescending { it.timestamp }
    }
}

/**
 * Servidor HTTP integrado para Android.
 */
class HttpServer(
    val context: Context,
    var port: Int = 8080
) {
    private val tag = "GTStore-HttpServer"
    private var serverSocket: ServerSocket? = null
    private var serverRunning = false
    private val activeConnectionsCount = AtomicInteger(0)
    private val threadPool = Executors.newCachedThreadPool()

    // =========================================================================
    // PROPRIEDADES E MÉTODOS DE COMPATIBILIDADE (MainActivity e GTStoreService)
    // =========================================================================

    /**
     * Função chamada pela MainActivity e GTStoreService: server.isRunning()
     */
    fun isRunning(): Boolean = serverRunning

    /**
     * Propriedade de acesso direto ao estado de execução: server.running
     */
    val running: Boolean
        get() = serverRunning

    /**
     * Retorna o endereço IP local do dispositivo na rede Wi-Fi / Hotspot.
     */
    val localAddress: String
        get() = getLocalIpAddress()

    /**
     * Quantidade de conexões ativas no momento.
     */
    val activeConnections: Int
        get() = activeConnectionsCount.get()

    /**
     * Fornece o snapshot de status esperado por MainActivity.kt e GTStoreService.kt.
     */
    fun getStatus(): ServerStatus {
        return ServerStatus(
            running = serverRunning,
            port = port,
            localAddress = localAddress,
            activeConnections = activeConnections
        )
    }

    // =========================================================================
    // CICLO DE VIDA DO SERVIDOR
    // =========================================================================

    fun start() {
        if (serverRunning) return
        serverRunning = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(tag, "Servidor GTSTORE iniciado na porta $port")
                while (serverRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    activeConnectionsCount.incrementAndGet()
                    threadPool.execute {
                        try {
                            handleConnection(clientSocket)
                        } finally {
                            activeConnectionsCount.decrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (serverRunning) {
                    Log.e(tag, "Erro no loop de escuta do servidor HTTP: ${e.message}")
                }
            } finally {
                serverRunning = false
            }
        }
    }

    fun stop() {
        serverRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(tag, "Servidor GTSTORE finalizado.")
        } catch (e: Exception) {
            Log.e(tag, "Erro ao encerrar socket: ${e.message}")
        }
    }

    // =========================================================================
    // PROCESSAMENTO DE REQUISIÇÕES
    // =========================================================================

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input))

            val requestLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            // Leitura de Cabeçalhos HTTP
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(":")
                if (colonIdx > 0) {
                    val key = line!!.substring(0, colonIdx).trim().lowercase()
                    val value = line!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            // Tratamento de preflight CORS (OPTIONS)
            if (method == "OPTIONS") {
                sendCorsOk(output)
                socket.close()
                return
            }

            // Leitura do corpo (quando método for POST)
            var body = ""
            if (method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = reader.read(charArray, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    body = String(charArray, 0, readTotal)
                }
            }

            routeRequest(method, fullPath, headers, body, output)

        } catch (e: Exception) {
            Log.w(tag, "Falha na requisição: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun routeRequest(
        method: String,
        fullPath: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream
    ) {
        val uriPath = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
        val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""
        val queryParams = parseQueryParams(queryString)

        when {
            // 1. Checkout (criado pelo console do PS4)
            method == "POST" && uriPath == "/api/order/create" -> {
                handleOrderCreate(body, output)
            }

            // 2. Consulta de status (polling pelo PS4)
            method == "GET" && uriPath == "/api/order/status" -> {
                val orderId = queryParams["id"]
                val host = headers["host"] ?: "127.0.0.1:$port"
                handleOrderStatus(orderId, host, output)
            }

            // 3. Listagem de pedidos na aba administrativa
            method == "GET" && uriPath == "/api/admin/orders" -> {
                handleAdminOrdersList(output)
            }

            // 4. Decisão do administrador (autorizar ou recusar)
            method == "POST" && uriPath == "/api/admin/order/approve" -> {
                handleAdminOrderDecision(body, output)
            }

            // 5. Download e Streaming de arquivos PKG com suporte a Range (HTTP 206)
            method == "GET" && uriPath.startsWith("/download/") -> {
                val filename = URLDecoder.decode(uriPath.removePrefix("/download/"), "UTF-8")
                handleFileStream(filename, headers, output)
            }

            // 6. Arquivos estáticos da pasta assets/ (HTML, JS, CSS, Imagens)
            method == "GET" -> {
                handleStaticAsset(uriPath, output)
            }

            else -> {
                sendJsonResponse(output, 404, """{"error": "Rota não encontrada"}""")
            }
        }
    }

    // =========================================================================
    // ENDPOINTS DE API (JSON)
    // =========================================================================

    private fun handleOrderCreate(body: String, output: OutputStream) {
        try {
            val json = JSONObject(body)
            val itemsArray = json.optJSONArray("items") ?: JSONArray()
            val targetIp = json.optString("ps4Ip", "")

            val items = mutableListOf<String>()
            for (i in 0 until itemsArray.length()) {
                items.add(itemsArray.getString(i))
            }

            val order = OrderManager.createOrder(items, targetIp)
            val response = JSONObject().apply {
                put("success", true)
                put("orderId", order.id)
                put("status", order.status.name.lowercase())
            }
            sendJsonResponse(output, 200, response.toString())
        } catch (e: Exception) {
            sendJsonResponse(output, 400, """{"success": false, "error": "${e.message}"}""")
        }
    }

    private fun handleOrderStatus(orderId: String?, host: String, output: OutputStream) {
        if (orderId.isNullOrBlank()) {
            sendJsonResponse(output, 400, """{"error": "ID ausente"}""")
            return
        }

        val order = OrderManager.getOrder(orderId)
        if (order == null) {
            sendJsonResponse(output, 404, """{"status": "not_found"}""")
            return
        }

        // Gera URLs públicas usando o Host da requisição recebida (WAN ou LAN)
        val itemsArray = JSONArray()
        order.items.forEach { item ->
            val pkgUrl = if (item.startsWith("http://") || item.startsWith("https://")) {
                item
            } else {
                "http://$host/download/$item"
            }
            itemsArray.put(pkgUrl)
        }

        val response = JSONObject().apply {
            put("id", order.id)
            put("status", order.status.name.lowercase())
            put("items", itemsArray)
        }
        sendJsonResponse(output, 200, response.toString())
    }

    private fun handleAdminOrdersList(output: OutputStream) {
        val pending = OrderManager.listPendingOrders()
        val array = JSONArray()

        pending.forEach { order ->
            val obj = JSONObject().apply {
                put("id", order.id)
                put("status", order.status.name.lowercase())
                put("ps4Ip", order.targetPs4Ip)
                put("timestamp", order.timestamp)
                val itemsArr = JSONArray()
                order.items.forEach { itemsArr.put(it) }
                put("items", itemsArr)
            }
            array.put(obj)
        }

        val res = JSONObject().apply { put("orders", array) }
        sendJsonResponse(output, 200, res.toString())
    }

    private fun handleAdminOrderDecision(body: String, output: OutputStream) {
        try {
            val json = JSONObject(body)
            val orderId = json.getString("id")
            val action = json.optString("action", "approve").lowercase()

            val newStatus = if (action == "approve") OrderStatus.APPROVED else OrderStatus.REJECTED
            val ok = OrderManager.updateStatus(orderId, newStatus)

            sendJsonResponse(output, 200, """{"success": $ok, "status": "${newStatus.name.lowercase()}"}""")
        } catch (e: Exception) {
            sendJsonResponse(output, 400, """{"success": false, "error": "${e.message}"}""")
        }
    }

    // =========================================================================
    // ENTREGA DE ARQUIVOS E STREAMING DE PKG COM SUPORTE A RANGE
    // =========================================================================

    private fun handleStaticAsset(rawPath: String, output: OutputStream) {
        var assetPath = if (rawPath == "/" || rawPath.isBlank()) "index.html" else rawPath.removePrefix("/")
        assetPath = assetPath.replace("..", "").trimStart('/')

        try {
            context.assets.open(assetPath).use { stream ->
                val bytes = stream.readBytes()
                val mime = getMimeType(assetPath)
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Cache-Control: no-cache, no-store, must-revalidate\r\n\r\n"
                output.write(header.toByteArray())
                output.write(bytes)
                output.flush()
            }
        } catch (_: Exception) {
            sendJsonResponse(output, 404, """{"error": "Arquivo não encontrado nos assets"}""")
        }
    }

    private fun handleFileStream(filename: String, headers: Map<String, String>, output: OutputStream) {
        val downloadFolder = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = File(downloadFolder, filename)

        if (!file.exists() || !file.canRead()) {
            sendJsonResponse(output, 404, """{"error": "Arquivo não encontrado para download"}""")
            return
        }

        val fileLength = file.length()
        val rangeHeader = headers["range"]

        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // Suporte a HTTP 206 Partial Content (requerido para o PS4)
                val ranges = rangeHeader.substring(6).split("-")
                val start = ranges[0].toLongOrNull() ?: 0L
                val end = if (ranges.size > 1 && ranges[1].isNotBlank()) ranges[1].toLong() else fileLength - 1
                val contentLength = end - start + 1

                val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes $start-$end/$fileLength\r\n" +
                        "Content-Length: $contentLength\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                output.write(header.toByteArray())

                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } else {
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: $fileLength\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                output.write(header.toByteArray())

                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != null && read != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.w(tag, "Conexão de streaming interrompida: ${e.message}")
        }
    }

    // =========================================================================
    // UTILITÁRIOS
    // =========================================================================

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun sendJsonResponse(output: OutputStream, code: Int, json: String) {
        val statusText = if (code == 200) "OK" else if (code == 404) "Not Found" else "Bad Request"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Accept, Range\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n\r\n"
        output.write(response.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun sendCorsOk(output: OutputStream) {
        val response = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Accept, Range\r\n" +
                "Access-Control-Max-Age: 86400\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isBlank()) return params
        query.split("&").forEach { pair ->
            val parts = pair.split("=")
            if (parts.size == 2) {
                params[URLDecoder.decode(parts[0], "UTF-8")] = URLDecoder.decode(parts[1], "UTF-8")
            } else if (parts.size == 1) {
                params[URLDecoder.decode(parts[0], "UTF-8")] = ""
            }
        }
        return params
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html", true) -> "text/html; charset=utf-8"
            path.endsWith(".js", true) -> "application/javascript; charset=utf-8"
            path.endsWith(".css", true) -> "text/css; charset=utf-8"
            path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
            path.endsWith(".png", true) -> "image/png"
            path.endsWith(".json", true) -> "application/json"
            path.endsWith(".bin", true) -> "application/octet-stream"
            path.endsWith(".pkg", true) -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }
}
