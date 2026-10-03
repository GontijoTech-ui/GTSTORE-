package com.gtstore

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class HttpServer(
    private val context: Context,
    private val port: Int = 8080,
    private val catalogProvider: () -> List<PkgMetadata>
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            addCorsHeaders(response)
            return response
        }

        val response = try {
            when {
                uri == "/api/status" && method == Method.GET -> handleStatus(session)
                uri == "/api/packages" && method == Method.GET -> handlePackages()
                
                // Solicitação do Carrinho em lote
                uri == "/api/request-cart-access" && method == Method.POST -> handleRequestCartAccess(session)
                
                // Checagem periódica do PS4 (a cada 10s)
                uri == "/api/check-access" && method == Method.GET -> handleCheckAccess(session)
                
                uri == "/api/install-dpi" && method == Method.POST -> handleInstallDpi(session)
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
            put("status", "online")
            put("port", port)
            put("clientIp", session.remoteIpAddress ?: "")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handlePackages(): Response {
        val packages = catalogProvider()
        val jsonArray = JSONArray()

        packages.forEach { pkg ->
            val obj = JSONObject().apply {
                put("id", pkg.id)
                put("title", pkg.title)
                put("contentId", pkg.contentId)
                put("size", pkg.size)
                put("catalogType", pkg.type.name)
                put("iconUrl", if (pkg.iconPath.isNotBlank()) "/covers/${pkg.id}.jpg" else "")
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

    private fun handleInstallDpi(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: ""
        val json = JSONObject(postData)

        val pkgId = json.optString("pkgId").trim()
        val targetIp = json.optString("ip").trim()
        val consoleId = json.optString("consoleId").trim()

        val pkg = catalogProvider().find { it.id == pkgId }
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"success":false,"error":"Pacote não encontrado"}"""
            )

        val key = pkg.contentId.substringBefore("_00-").ifBlank { pkg.id }
        if (consoleId.isNotBlank() && !AccessManager.isAccessApproved(consoleId, key)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json",
                """{"success":false,"error":"Acesso expirado (15 min) ou não autorizado."}"""
            )
        }

        return try {
            DpiInstaller.sendInstallRequest(targetIp, pkg)
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success":false,"error":"${e.message}"}"""
            )
        }
    }

    private fun handleDownloadPkg(session: IHTTPSession): Response {
        val pkgId = session.parameters["id"]?.firstOrNull() ?: ""
        val pkg = catalogProvider().find { it.id == pkgId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Arquivo não encontrado")

        if (pkg.directUrl.startsWith("http://", ignoreCase = true) || pkg.directUrl.startsWith("https://", ignoreCase = true)) {
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", pkg.directUrl)
            return response
        }

        val file = File(pkg.directUrl)
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
        val file = File(File(context.filesDir, "covers"), fileName)

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
