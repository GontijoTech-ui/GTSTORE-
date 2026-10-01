package com.gtstore

import android.content.Context
import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object RemotePkgReader {

    data class Result(
        val url: String,
        val size: Long,
        val title: String,
        val contentId: String,
        val category: String,
        val version: String,
        val digest: String,
        val digestMatches: Boolean,
        val icon: ByteArray?
    )

    private const val MAGIC = 0x7F434E54
    private const val ID_PARAM_SFO = 0x1000
    private const val ID_ICON0_PNG = 0x1200
    private const val HEADER_SIZE = 0x1000
    private const val DIGEST_OFFSET = 0xFE0
    private const val MAX_ENTRY_COUNT = 4096
    private const val MAX_ENTRY_DATA = 4_000_000

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 25_000

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun read(context: Context, rawUrl: String): Result? {
        val url = rawUrl.trim()
        AppLogger.log("--------------------------------------------------")
        AppLogger.log("[RemotePkgReader] Iniciando leitura remota de PKG:")
        AppLogger.log("[RemotePkgReader] URL Alvo: $url")

        if (!isValidUrl(url)) {
            AppLogger.log("[RemotePkgReader] FALHA: URL inválida estruturalmente.")
            return null
        }

        return try {
            val size = determineSize(url)
            AppLogger.log("[RemotePkgReader] Tamanho obtido: $size bytes")

            if (size < HEADER_SIZE) {
                AppLogger.log("[RemotePkgReader] FALHA: Tamanho ($size) menor que o cabeçalho mínimo ($HEADER_SIZE).")
                return null
            }

            val head = range(url, 0, HEADER_SIZE - 1L)
            AppLogger.log("[RemotePkgReader] Cabeçalho lido: ${head.size}/$HEADER_SIZE bytes")

            if (head.size != HEADER_SIZE) {
                AppLogger.log("[RemotePkgReader] FALHA: Cabeçalho incompleto.")
                return null
            }

            val res = parsePkg(url, size, head)
            if (res != null) {
                AppLogger.log("[RemotePkgReader] SUCESSO! PKG: ${res.title} [${res.contentId}] v${res.version}")
            } else {
                AppLogger.log("[RemotePkgReader] FALHA: parsePkg retornou nulo (Magic ou SFO inválido).")
            }
            res
        } catch (e: Exception) {
            AppLogger.log("[RemotePkgReader] EXCEÇÃO na leitura: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    private fun parsePkg(
        url: String,
        size: Long,
        head: ByteArray
    ): Result? {
        val h = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        val magic = h.getInt(0)

        AppLogger.log("[RemotePkgReader] Magic lido: 0x${Integer.toHexString(magic).uppercase()} (Esperado: 0x7F434E54)")

        if (magic != MAGIC) {
            val snippet = String(head.take(80).toByteArray(), Charsets.UTF_8)
                .replace("\n", " ")
                .replace("\r", "")
            AppLogger.log("[RemotePkgReader] FALHA: Magic inválido. Resposta recebida: \"$snippet\"")
            return null
        }

        val entryCount = h.getInt(0x10)
        AppLogger.log("[RemotePkgReader] Entradas na tabela: $entryCount")

        if (entryCount !in 1..MAX_ENTRY_COUNT) {
            AppLogger.log("[RemotePkgReader] FALHA: Contagem de entradas fora dos limites.")
            return null
        }

        val tableOffset = h.getInt(0x18).toLong() and 0xFFFFFFFFL
        val tableSize = entryCount.toLong() * 0x20L

        if (tableOffset < 0L || tableSize <= 0L || tableOffset + tableSize > size) {
            AppLogger.log("[RemotePkgReader] FALHA: Tabela fora dos limites.")
            return null
        }

        val table = range(url, tableOffset, tableOffset + tableSize - 1L)
        if (table.size != tableSize.toInt()) {
            AppLogger.log("[RemotePkgReader] FALHA: Tabela de índices incompleta.")
            return null
        }

        val t = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN)
        var sfo: ByteArray? = null
        var icon: ByteArray? = null

        for (i in 0 until entryCount) {
            val base = i * 0x20
            val id = t.getInt(base)
            val dataOffset = t.getInt(base + 16).toLong() and 0xFFFFFFFFL
            val dataSize = t.getInt(base + 20).toLong() and 0xFFFFFFFFL

            if (dataSize <= 0L || dataSize > MAX_ENTRY_DATA) continue
            if (dataOffset < 0L || dataOffset + dataSize > size) continue

            when (id) {
                ID_PARAM_SFO -> sfo = range(url, dataOffset, dataOffset + dataSize - 1L)
                ID_ICON0_PNG -> icon = range(url, dataOffset, dataOffset + dataSize - 1L)
            }
        }

        val fields = parseSfo(sfo ?: run {
            AppLogger.log("[RemotePkgReader] FALHA: PARAM.SFO não localizado.")
            return null
        })

        val contentId = fields["CONTENT_ID"]?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            AppLogger.log("[RemotePkgReader] FALHA: CONTENT_ID ausente no SFO.")
            return null
        }

        val category = fields["CATEGORY"]?.trim()?.takeIf { it.isNotEmpty() } ?: "gd"
        val title = fields["TITLE"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: fields["TITLE_00"]?.trim()
            ?: fields["ATTRIBUTE_TITLE"]?.trim()
            ?: contentId

        val version = fields["APP_VER"]?.trim() ?: fields["VERSION"]?.trim() ?: ""

        val storedDigest = head.copyOfRange(DIGEST_OFFSET, DIGEST_OFFSET + 32)
        val computedDigest = MessageDigest.getInstance("SHA-256").digest(head.copyOfRange(0, DIGEST_OFFSET))
        val digest = storedDigest.joinToString("") { "%02X".format(it) }

        return Result(
            url = url,
            size = size,
            title = title,
            contentId = contentId,
            category = category,
            version = version,
            digest = digest,
            digestMatches = storedDigest.contentEquals(computedDigest),
            icon = icon
        )
    }

    private fun determineSize(url: String): Long {
        AppLogger.log("[RemotePkgReader] Consultando tamanho via GET Range=0-0...")
        val connection = openConnection(url, "GET")
        try {
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()

            val code = connection.responseCode
            val type = connection.contentType
            AppLogger.log("[RemotePkgReader] Resposta HTTP: $code | Content-Type: $type")

            val contentRange = connection.getHeaderField("Content-Range")
            AppLogger.log("[RemotePkgReader] Header Content-Range: $contentRange")

            val totalFromRange = parseContentRangeTotal(contentRange)
            if (totalFromRange > 0L) return totalFromRange

            val length = connection.contentLengthLong
            if (length > 0L) return length
        } catch (e: Exception) {
            AppLogger.log("[RemotePkgReader] Aviso Range: ${e.message}")
        } finally {
            connection.disconnect()
        }

        AppLogger.log("[RemotePkgReader] Tentando fallback via HEAD...")
        val headConnection = openConnection(url, "HEAD")
        try {
            headConnection.connect()
            val code = headConnection.responseCode
            AppLogger.log("[RemotePkgReader] Resposta HEAD HTTP: $code")
            val length = headConnection.contentLengthLong
            if (length > 0L) return length
            throw IOException("Servidor não retornou o tamanho do arquivo.")
        } finally {
            headConnection.disconnect()
        }
    }

    private fun range(url: String, start: Long, end: Long): ByteArray {
        val connection = openConnection(url, "GET")
        try {
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode ao ler intervalo $start-$end")
            }

            val expected = (end - start + 1L).toInt()
            val result = ByteArray(expected)
            var offset = 0

            connection.inputStream.use { input ->
                while (offset < expected) {
                    val read = input.read(result, offset, expected - offset)
                    if (read <= 0) break
                    offset += read
                }
            }

            return if (offset == expected) result else result.copyOf(offset)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSfo(data: ByteArray): Map<String, String> {
        if (data.size < 20) return emptyMap()

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x46535000) return emptyMap()

        val keyTable = bb.getInt(8)
        val dataTable = bb.getInt(12)
        val count = bb.getInt(16)

        if (count < 0 || count > 4096) return emptyMap()

        val out = HashMap<String, String>(count)

        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > data.size) break

            val keyOffset = bb.getShort(e).toInt() and 0xFFFF
            val format = bb.getShort(e + 2).toInt() and 0xFFFF
            val length = bb.getInt(e + 4)
            val dataOffset = bb.getInt(e + 12)

            if (format != 0x0204 && format != 0x0004) continue
            if (length < 0) continue

            val keyStart = keyTable + keyOffset
            if (keyStart < 0 || keyStart >= data.size) continue

            var keyEnd = keyStart
            while (keyEnd < data.size && data[keyEnd] != 0.toByte()) {
                keyEnd++
            }

            val valueStart = dataTable + dataOffset
            val valueEnd = valueStart + length
            if (valueStart < 0 || valueEnd < valueStart || valueEnd > data.size) continue

            val key = String(data, keyStart, keyEnd - keyStart, Charsets.UTF_8)
            val value = String(data, valueStart, length, Charsets.UTF_8).trimEnd('\u0000')
            out[key] = value
        }

        return out
    }

    private fun openConnection(rawUrl: String, method: String): HttpURLConnection {
        val sanitizedUrl = rawUrl
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace(" ", "%20")

        val connection = URL(sanitizedUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        connection.useCaches = false

        connection.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Connection", "keep-alive")
        connection.setRequestProperty("Referer", sanitizedUrl)

        try {
            val cookie = CookieManager.getInstance().getCookie(sanitizedUrl)
            if (!cookie.isNullOrBlank()) {
                AppLogger.log("[RemotePkgReader] Injetando Cookies: $cookie")
                connection.setRequestProperty("Cookie", cookie)
            } else {
                AppLogger.log("[RemotePkgReader] Nenhum cookie registrado para a URL.")
            }
        } catch (e: Exception) {
            AppLogger.log("[RemotePkgReader] Erro ao checar cookies: ${e.message}")
        }

        return connection
    }

    private fun parseContentRangeTotal(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        val slash = value.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= value.length) return -1L
        return value.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    private fun isValidUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        val isHttp = trimmed.startsWith("http://", ignoreCase = true)
        val isHttps = trimmed.startsWith("https://", ignoreCase = true)
        if (!isHttp && !isHttps) return false

        return try {
            val sanitized = trimmed
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace(" ", "%20")
            val uri = URI(sanitized)
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            try {
                val u = URL(trimmed)
                !u.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}
