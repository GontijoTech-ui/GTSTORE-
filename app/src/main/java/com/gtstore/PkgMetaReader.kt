package com.gtstore

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Lê TITLE, CONTENT_ID, CATEGORY, icon0.png e o digest do cabeçalho de um PKG do PS4 via SAF.
 */
object PkgMetaReader {

    data class Meta(
        val title: String,
        val contentId: String,
        val category: String, // "gd" (GAME), "gp" (UPDATE), "ac" (DLC)...
        val icon: ByteArray?,
        val digest: String,         // 64 caracteres hex maiúsculos (32 bytes em 0xFE0)
        val digestMatches: Boolean  // digest guardado == SHA-256(primeiros 0xFE0 bytes)
    ) {
        val bgftType: String get() = "PS4" + category.uppercase()
    }

    private const val MAGIC = 0x7F434E54
    private const val ID_PARAM_SFO = 0x1000
    private const val ID_ICON0_PNG = 0x1200
    private const val HEADER_SIZE = 0x1000
    private const val DIGEST_OFFSET = 0xFE0

    fun read(context: Context, uri: Uri): Meta? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.let { parse(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun parse(ch: FileChannel): Meta? {
        val head = readAt(ch, 0, HEADER_SIZE)
        val h = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        if (h.getInt(0) != MAGIC) return null

        val entryCount = h.getInt(0x10)
        val tableOffset = h.getInt(0x18).toLong() and 0xFFFFFFFFL
        if (entryCount !in 1..4096) return null

        val t = ByteBuffer.wrap(readAt(ch, tableOffset, entryCount * 0x20)).order(ByteOrder.BIG_ENDIAN)

        var sfo: ByteArray? = null
        var icon: ByteArray? = null
        for (i in 0 until entryCount) {
            val base = i * 0x20
            val id = t.getInt(base)
            val dataOffset = t.getInt(base + 16).toLong() and 0xFFFFFFFFL
            val dataSize = t.getInt(base + 20).toLong() and 0xFFFFFFFFL
            if (dataSize == 0L || dataSize > 4_000_000L) continue
            when (id) {
                ID_PARAM_SFO -> sfo = readAt(ch, dataOffset, dataSize.toInt())
                ID_ICON0_PNG -> icon = readAt(ch, dataOffset, dataSize.toInt())
            }
        }

        val p = parseSfo(sfo ?: return null)

        val stored = head.copyOfRange(DIGEST_OFFSET, DIGEST_OFFSET + 32)
        val computed = MessageDigest.getInstance("SHA-256").digest(head.copyOfRange(0, DIGEST_OFFSET))

        return Meta(
            title = p["TITLE"] ?: return null,
            contentId = p["CONTENT_ID"] ?: return null,
            category = p["CATEGORY"] ?: return null,
            icon = icon,
            digest = stored.joinToString("") { "%02X".format(it) },
            digestMatches = stored.contentEquals(computed)
        )
    }

    private fun readAt(ch: FileChannel, pos: Long, size: Int): ByteArray {
        val buf = ByteBuffer.allocate(size)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) throw java.io.EOFException()
            p += n
        }
        return buf.array()
    }

    /** PARAM.SFO (little-endian); campos em formato de texto (0x0204). */
    private fun parseSfo(b: ByteArray): Map<String, String> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (b.size < 20 || bb.getInt(0) != 0x46535000) return emptyMap()
        val keyTable = bb.getInt(8)
        val dataTable = bb.getInt(12)
        val count = bb.getInt(16)
        val out = HashMap<String, String>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            val keyOff = bb.getShort(e).toInt() and 0xFFFF
            val fmt = bb.getShort(e + 2).toInt() and 0xFFFF
            val len = bb.getInt(e + 4)
            val dataOff = bb.getInt(e + 12)
            if (fmt != 0x0204) continue
            val ks = keyTable + keyOff
            var ke = ks
            while (ke < b.size && b[ke] != 0.toByte()) ke++
            out[String(b, ks, ke - ks, Charsets.UTF_8)] =
                String(b, dataTable + dataOff, len, Charsets.UTF_8).trimEnd('\u0000')
        }
        return out
    }
}
