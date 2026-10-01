package com.gtstore

import android.content.Context
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

    // Timeouts mais ágeis para evitar congelamentos longos em falhas de conexão
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000

    fun read(context: Context, rawUrl: String): Result? {
        val url = rawUrl.trim()
        if (!isValidUrl(url)) return null

        return try {
            val size = determineSize(url)
            if (size < HEADER_SIZE) return null

            val head = range(url, 0, HEADER_SIZE - 1L)
            if (head.size != HEADER_SIZE) return null

            parsePkg(url, size, head)
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePkg(
        url: String,
        size: Long,
        head: ByteArray
    ): Result? {
        val h = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        if (h.getInt(0) != MAGIC) return null

        val entryCount = h.getInt(0x10)
        if (entryCount !in 1..MAX_ENTRY_COUNT) return null

        val tableOffset = h.getInt(0x18).toLong() and 0xFFFFFFFFL
        val tableSize = entryCount.toLong() * 0x20L

        if (tableOffset < 0L || tableSize <= 0L || tableOffset + tableSize > size) {
            return null
        }

        val table = range(url, tableOffset, tableOffset + tableSize - 1L)
        if (table.size != tableSize.toInt()) return null

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

        val fields = parseSfo(sfo ?: return null)
        val contentId = fields["CONTENT_ID"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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
        val headConnection = openConnection(url, "HEAD")
        try {
            headConnection.connect()
            val length = headConnection.contentLengthLong
            if (length > 0L) return length
        } finally {
            headConnection.disconnect()
        }

        val connection = openConnection(url, "GET")
        try {
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.connect()

            val contentRange = connection.getHeaderField("Content-Range")
            val totalFromRange = parseContentRangeTotal(contentRange)
            if (totalFromRange > 0L) return totalFromRange

            val length = connection.contentLengthLong
            if (length > 0L) return length

            throw IOException("Servidor não informou o tamanho do arquivo.")
        } finally {
            connection.disconnect()
        }
    }

    private fun range(
        url: String,
        start: Long,
        end: Long
    ): ByteArray {
        require(start >= 0L)
        require(end >= start)

        val connection = openConnection(url, "GET")
        try {
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode")
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

            if (responseCode == HttpURLConnection.HTTP_PARTIAL && offset != expected) {
                throw IOException("Range incompleto: esperado=$expected recebido=$offset")
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
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IOException("Somente HTTP e HTTPS são suportados.")
        }

        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        return connection
    }

    private fun parseContentRangeTotal(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        val slash = value.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= value.length) return -1L
        return value.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    private fun isValidUrl(rawUrl: String): Boolean {
        return try {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
