package com.gtstore

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modelo de dados com o estado atual do servidor HTTP.
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
 * Gestor thread-safe em memória com suporte a observadores para a interface nativa.
 */
object OrderManager {
    private val orders = ConcurrentHashMap<String, Order>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Regista um ouvinte para ser chamado sempre que um pedido for criado ou alterado.
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove o ouvinte quando a tela/aba for destruída ou pausada.
     */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (e: Exception) {
                Log.e("OrderManager", "Erro ao notificar ouvinte: ${e.message}")
            }
        }
    }

    fun createOrder(items: List<String>, targetIp: String): Order {
        val id = UUID.randomUUID().toString().substring(0, 8).uppercase()
        val order = Order(id = id, items = items, targetPs4Ip = targetIp)
        orders[id] = order
        notifyListeners() // Avisa a interface do Android imediatamente
        return order
    }

    fun getOrder(id: String): Order? = orders[id]

    fun updateStatus(id: String, status: OrderStatus): Boolean {
        val order = orders[id] ?: return false
        order.status = status
        notifyListeners() // Avisa a interface da mudança de estado
        return true
    }

    fun listPendingOrders(): List<Order> {
        return orders.values.filter { it.status == OrderStatus.PENDING }.sortedByDescending { it.timestamp }
    }

    fun listAllOrders(): List<Order> {
        return orders.values.sortedByDescending { it.timestamp }
    }
}

/**
 * Servidor HTTP integrado para Android associado ao CatalogManager.
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

    // Acesso ao catálogo persistido
    private val catalogManager by lazy {
        CatalogManager(context.applicationContext)
    }

    fun isRunning(): Boolean = serverRunning

    val running: Boolean
        get() = serverRunning

    val localAddress: String
        get() = getLocalIpAddress()

    val activeConnections: Int
        get() = activeConnectionsCount.get()

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
                    Log.e(tag, "Erro no ciclo de escuta do servidor HTTP: ${e.message}")
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
            Log.e(tag, "Erro ao encerrar o socket: ${e.message}")
        }
    }

    // =========================================================================
    // PROCESSAMENTO DE REQUISIÇÕES
    // =========================================================================

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))

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

            if (method == "OPTIONS") {
                sendCorsOk(output)
                socket.close()
                return
            }

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

            routeRequest(method, fullPath, headers, body, socket, output)

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
        socket: Socket,
        output: OutputStream
    ) {
        val uriPath = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
        val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""
        val queryParams = parseQueryParams(queryString)

        when {
            // Estado do servidor
            method == "GET" && uriPath == "/api/status" -> {
                val clientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"
                val res = JSONObject().apply {
                    put("online", true)
                    put("clientIp", clientIp)
                }
                sendJsonResponse(output, 200, res.toString())
            }

            // Catálogo obtido via CatalogManager com as respetivas URLs externas
            method == "GET" && uriPath == "/api/packages" -> {
                handleGetPackages(output)
            }

            // Capa/Ícone do item registado no catálogo
            method == "GET" && uriPath.startsWith("/api/package-icon/") -> {
                val id = uriPath.removePrefix("/api/package-icon/").split("/").firstOrNull()?.toIntOrNull()
                if (id != null) {
                    handlePackageIcon(id, output)
                } else {
                    sendJsonResponse(output, 400, """{"error": "ID inválido"}""")
                }
            }

            // Manifesto JSON dinâmico para a instalação direta via DPI
            method == "GET" && (uriPath.startsWith("/json/") || uriPath.startsWith("/json-public/")) -> {
                val rawId = uriPath
                    .removePrefix("/json-public/")
                    .removePrefix("/json/")
                    .removeSuffix(".json")
                handleManifestJson(rawId, output)
            }

            // Checkout / Criação de Pedido
            method == "POST" && uriPath == "/api/order/create" -> {
                handleOrderCreate(body, output)
            }

            // Polling de estado do pedido
            method == "GET" && uriPath == "/api/order/status" -> {
                val orderId = queryParams["id"]
                handleOrderStatus(orderId, output)
            }

            // Listagem de pedidos no painel administrativo
            method == "GET" && uriPath == "/api/admin/orders" -> {
                handleAdminOrdersList(output)
            }

            // Decisão administrativa (aprovação ou rejeição)
            method == "POST" && uriPath == "/api/admin/order/approve" -> {
                handleAdminOrderDecision(body, output)
            }

            // Ficheiros estáticos da pasta assets/ (HTML, JS, CSS, Imagens)
            method == "GET" -> {
                handleStaticAsset(uriPath, output)
            }

            else -> {
                sendJsonResponse(output, 404, """{"error": "Rota não encontrada"}""")
            }
        }
    }

    // =========================================================================
    // ENDPOINTS DE CATÁLOGO E MANIFESTO
    // =========================================================================

    private fun handleGetPackages(output: OutputStream) {
        val packages = catalogManager.getAll()
        val array = JSONArray()

        for (item in packages) {
            val icon = catalogManager.getIcon(item)
            val hasIcon = icon != null && icon.isNotEmpty()
            array.put(
                JSONObject().apply {
                    put("id", item.catalogIndex)
                    put("catalogIndex", item.catalogIndex)
                    put("index", item.indexString)
                    put("title", item.title)
                    put("fileName", item.fileName)
                    put("file", item.indexString + ".pkg")
                    put("size", item.size)
                    put("version", item.version)
                    put("category", item.category)
                    put("type", item.type)
                    put("contentId", item.contentId)
                    put("digest", item.digest)
                    put("digestMatches", item.digestMatches)
                    put("url", item.url)
                    put("iconUrl", if (hasIcon) "/api/package-icon/${item.catalogIndex}" else "")
                }
            )
        }

        val res = JSONObject().apply {
            put("count", packages.size)
            put("items", array)
            put("packages", array)
        }
        sendJsonResponse(output, 200, res.toString())
    }

    private fun handlePackageIcon(packageId: Int, output: OutputStream) {
        val item = catalogManager.getByIndex(packageId)
        if (item == null) {
            sendJsonResponse(output, 404, """{"error": "Item não encontrado"}""")
            return
        }

        val icon = catalogManager.getIcon(item)
        if (icon == null || icon.isEmpty()) {
            sendJsonResponse(output, 404, """{"error": "Ícone indisponível"}""")
            return
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: ${icon.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Cache-Control: max-age=86400\r\n\r\n"
        output.write(header.toByteArray())
        output.write(icon)
        output.flush()
    }

    private fun handleManifestJson(rawId: String, output: OutputStream) {
        val idx = rawId.toIntOrNull()
        val item = if (idx != null) {
            catalogManager.getByIndex(idx)
        } else {
            catalogManager.getByContentId(rawId)
        }

        if (item == null) {
            sendJsonResponse(output, 404, """{"error": "Item do catálogo não encontrado"}""")
            return
        }

        if (item.url.isBlank()) {
            sendJsonResponse(output, 422, """{"error": "Item sem URL configurada"}""")
            return
        }

        val digest = if (item.digest.isNotBlank()) item.digest else "0000000000000000000000000000000000000000000000000000000000000000"

        val manifest = JSONObject().apply {
            put("originalFileSize", item.size)
            put("packageDigest", digest)
            put("numberOfSplitFiles", 1)
            put("pieces", JSONArray().put(
                JSONObject().apply {
                    put("url", item.url)
                    put("fileOffset", 0)
                    put("fileSize", item.size)
                    put("hashValue", "0000000000000000000000000000000000000000")
                }
            ))
            put("type", "direct")
            put("packages", JSONArray().put(item.url))
        }

        sendJsonResponse(output, 200, manifest.toString())
    }

    // =========================================================================
    // GESTÃO DE PEDIDOS (ORDERMANAGER)
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
            Log.i(tag, ">>> [NOVO PEDIDO CHEGOU] ID: #${order.id} | Itens: ${items.size} | Alvo: $targetIp")

            // Dispara Broadcast interno para a interface da Activity
            try {
                val intent = Intent("com.gtstore.ORDER_CHANGED").apply {
                    putExtra("orderId", order.id)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(tag, "Não foi possível disparar Broadcast: ${e.message}")
            }

            val response = JSONObject().apply {
                put("success", true)
                put("orderId", order.id)
                put("status", order.status.name.lowercase())
            }
            sendJsonResponse(output, 200, response.toString())
        } catch (e: Exception) {
            Log.e(tag, "Falha em handleOrderCreate: ${e.message}")
            sendJsonResponse(output, 400, """{"success": false, "error": "${e.message}"}""")
        }
    }

    private fun handleOrderStatus(orderId: String?, output: OutputStream) {
        if (orderId.isNullOrBlank()) {
            sendJsonResponse(output, 400, """{"error": "ID ausente"}""")
            return
        }

        val order = OrderManager.getOrder(orderId)
        if (order == null) {
            sendJsonResponse(output, 404, """{"status": "not_found"}""")
            return
        }

        val itemsArray = JSONArray()
        order.items.forEach { itemsArray.put(it) }

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
            Log.i(tag, ">>> [DECISAO DE PEDIDO] ID: #$orderId -> $newStatus")

            try {
                val intent = Intent("com.gtstore.ORDER_CHANGED").apply {
                    putExtra("orderId", orderId)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (_: Exception) {}

            sendJsonResponse(output, 200, """{"success": $ok, "status": "${newStatus.name.lowercase()}"}""")
        } catch (e: Exception) {
            sendJsonResponse(output, 400, """{"success": false, "error": "${e.message}"}""")
        }
    }

    // =========================================================================
    // FICHEIROS ESTÁTICOS
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
            sendJsonResponse(output, 404, """{"error": "Ficheiro não encontrado nos assets"}""")
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
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
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
