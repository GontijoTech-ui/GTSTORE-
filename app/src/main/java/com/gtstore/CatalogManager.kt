package com.gtstore

import android.content.Context
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL

class CatalogManager(
    private val context: Context
) {

    data class OperationResult(
        val success: Boolean,
        val message: String,
        val item: CatalogItem? = null
    )

    companion object {
        private const val PREFS = "GTSTORE_CATALOG_MANAGER"
        private const val KEY_ITEMS = "items"
        private const val KEY_NEXT_INDEX = "next_index"
        private const val ICON_DIR = "catalog_icons"

        @Volatile
        private var memoryCache: List<CatalogItem>? = null

        private val iconCache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
    }

    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    // ========================================================
    // URL DIRETA
    // ========================================================

    @Synchronized
    fun registerOrUpdate(
        rawUrl: String
    ): OperationResult {
        val url = rawUrl.trim()
        AppLogger.log("--------------------------------------------------")
        AppLogger.log("[CatalogManager] registerOrUpdate recebido: \"$url\"")

        if (!isValidUrl(url)) {
            AppLogger.log("[CatalogManager] FALHA: isValidUrl retornou false para: \"$url\"")
            return OperationResult(
                false,
                "URL inválida. Use http:// ou https://."
            )
        }

        AppLogger.log("[CatalogManager] URL válida. Encaminhando para RemotePkgReader.read...")
        val remote = RemotePkgReader.read(context, url)

        if (remote == null) {
            AppLogger.log("[CatalogManager] FALHA: RemotePkgReader.read retornou null.")
            return OperationResult(
                false,
                "Não foi possível reconhecer um PKG nessa URL."
            )
        }

        AppLogger.log("[CatalogManager] PKG lido com sucesso. Gravando no catálogo...")
        return saveRemotePkg(
            remote = remote,
            sourceUrl = "",
            fileName = ""
        )
    }

    // ========================================================
    // URL CAPTURADA PELO WEBVIEW
    // ========================================================

    @Synchronized
    fun registerOrUpdateCaptured(
        sourceUrl: String,
        directUrl: String,
        fileName: String
    ): OperationResult {
        val normalizedSource = sourceUrl.trim()
        val normalizedDirect = directUrl.trim()
        val normalizedFileName = fileName.trim()

        AppLogger.log("--------------------------------------------------")
        AppLogger.log("[CatalogManager] registerOrUpdateCaptured acionado!")
        AppLogger.log("[CatalogManager] sourceUrl: \"$normalizedSource\"")
        AppLogger.log("[CatalogManager] directUrl: \"$normalizedDirect\"")
        AppLogger.log("[CatalogManager] fileName: \"$normalizedFileName\"")

        if (!isValidUrl(normalizedSource)) {
            AppLogger.log("[CatalogManager] FALHA: URL de origem inválida ($normalizedSource)")
            return OperationResult(
                false,
                "URL de origem inválida."
            )
        }

        if (!isValidUrl(normalizedDirect)) {
            AppLogger.log("[CatalogManager] FALHA: URL de download direto inválida ($normalizedDirect)")
            return OperationResult(
                false,
                "URL de download inválida."
            )
        }

        AppLogger.log("[CatalogManager] URLs validadas. Chamando RemotePkgReader.read...")
        val remote = RemotePkgReader.read(context, normalizedDirect)

        if (remote == null) {
            AppLogger.log("[CatalogManager] FALHA: RemotePkgReader retornou null para a URL capturada.")
            return OperationResult(
                false,
                "O link foi capturado, mas não foi possível reconhecer um PKG nele."
            )
        }

        AppLogger.log("[CatalogManager] PKG reconhecido. Salvando item capturado...")
        return saveRemotePkg(
            remote = remote,
            sourceUrl = normalizedSource,
            fileName = normalizedFileName
        )
    }

    // ========================================================
    // SALVAMENTO CENTRAL
    // ========================================================

    private fun saveRemotePkg(
        remote: RemotePkgReader.Result,
        sourceUrl: String,
        fileName: String
    ): OperationResult {

        val items = loadItemsInternal()

        val existing =
            items.firstOrNull {
                it.contentId.equals(
                    remote.contentId,
                    ignoreCase = true
                ) &&
                it.category.equals(
                    remote.category,
                    ignoreCase = true
                ) &&
                normalizeVersion(it.version) ==
                normalizeVersion(remote.version)
            }

        if (existing != null) {
            AppLogger.log("[CatalogManager] Atualizando item existente: ${existing.title} (Índice: ${existing.indexString})")
            val updated =
                existing.copy(
                    url = remote.url,
                    sourceUrl =
                        if (sourceUrl.isNotBlank()) {
                            sourceUrl
                        } else {
                            existing.sourceUrl
                        },
                    fileName =
                        if (fileName.isNotBlank()) {
                            fileName
                        } else {
                            existing.fileName
                        },
                    size = remote.size,
                    digest = remote.digest,
                    digestMatches = remote.digestMatches
                )

            val updatedItems =
                items.map {
                    if (it.catalogIndex == existing.catalogIndex) {
                        updated
                    } else {
                        it
                    }
                }

            saveItems(updatedItems)
            saveIcon(updated.catalogIndex, remote.icon)

            return OperationResult(
                true,
                "URL atualizada para [${updated.type}] ${updated.title}. Índice ${existing.indexString} preservado.",
                updated
            )
        }

        // NOVO ITEM
        val newIndex = nextIndex()
        val itemType = classify(remote.category)
        val formattedTitle =
            formatItemTitle(
                remote.title,
                itemType,
                remote.version
            )

        AppLogger.log("[CatalogManager] Criando novo registro: $formattedTitle (Tipo: $itemType, Novo Índice: $newIndex)")

        val item =
            CatalogItem(
                catalogIndex = newIndex,
                title = formattedTitle,
                contentId = remote.contentId,
                category = remote.category,
                type = itemType,
                version = remote.version,
                size = remote.size,
                digest = remote.digest,
                digestMatches = remote.digestMatches,
                url = remote.url,
                sourceUrl = sourceUrl,
                fileName = fileName,
                iconFile = iconFileName(newIndex)
            )

        saveIcon(newIndex, remote.icon)
        saveItems(items + item)

        return OperationResult(
            true,
            "PKG reconhecido como [$itemType] e adicionado com o índice ${item.indexString}.",
            item
        )
    }

    // ========================================================
    // CONSULTAS OTIMIZADAS COM MEMORY CACHE
    // ========================================================

    fun getAll(): List<CatalogItem> {
        return memoryCache ?: synchronized(this) {
            memoryCache ?: loadItemsInternal().sortedBy { it.catalogIndex }.also {
                memoryCache = it
            }
        }
    }

    fun getByIndex(
        catalogIndex: Int
    ): CatalogItem? {
        return getAll()
            .firstOrNull {
                it.catalogIndex == catalogIndex
            }
    }

    fun getByContentId(
        contentId: String
    ): CatalogItem? {
        return getAll()
            .firstOrNull {
                it.contentId.equals(
                    contentId,
                    ignoreCase = true
                )
            }
    }

    // ========================================================
    // ÍCONE OTIMIZADO COM LRUCACHE
    // ========================================================

    fun getIcon(
        item: CatalogItem
    ): ByteArray? {

        if (item.iconFile.isBlank()) {
            return null
        }

        iconCache.get(item.iconFile)?.let { return it }

        val file =
            File(
                context.filesDir,
                "$ICON_DIR/${item.iconFile}"
            )

        return try {
            if (file.exists()) {
                val bytes = file.readBytes()
                iconCache.put(item.iconFile, bytes)
                bytes
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ========================================================
    // TÍTULO
    // ========================================================

    private fun formatItemTitle(
        originalTitle: String,
        type: String,
        version: String
    ): String {

        val cleanTitle = originalTitle.trim()

        return when (type) {
            PkgCatalogItem.TYPE_UPDATE -> {
                val v =
                    if (version.isNotBlank()) {
                        " v$version"
                    } else {
                        ""
                    }

                if (
                    cleanTitle.contains("update", ignoreCase = true) ||
                    cleanTitle.contains("patch", ignoreCase = true)
                ) {
                    cleanTitle
                } else {
                    "$cleanTitle [UPDATE$v]"
                }
            }

            PkgCatalogItem.TYPE_DLC -> {
                if (cleanTitle.contains("dlc", ignoreCase = true)) {
                    cleanTitle
                } else {
                    "$cleanTitle [DLC]"
                }
            }

            else -> cleanTitle
        }
    }

    // ========================================================
    // CLASSIFICAÇÃO
    // ========================================================

    private fun normalizeVersion(
        v: String?
    ): String {
        return v
            ?.trim()
            ?.removePrefix("0")
            ?.ifBlank { "0" }
            ?: "0"
    }

    private fun classify(
        category: String
    ): String {
        return when (category.trim().lowercase()) {
            "gd", "gda" -> PkgCatalogItem.TYPE_GAME
            "gp", "gpe" -> PkgCatalogItem.TYPE_UPDATE
            "ac" -> PkgCatalogItem.TYPE_DLC
            else -> PkgCatalogItem.TYPE_OTHER
        }
    }

    // ========================================================
    // ÍNDICE
    // ========================================================

    private fun nextIndex(): Int {
        val current = prefs.getInt(KEY_NEXT_INDEX, 1)
        prefs.edit().putInt(KEY_NEXT_INDEX, current + 1).apply()
        return current
    }

    // ========================================================
    // CARREGAMENTO INTERNO (DISCO)
    // ========================================================

    private fun loadItemsInternal(): List<CatalogItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        CatalogItem(
                            catalogIndex = item.getInt("catalogIndex"),
                            title = item.optString("title"),
                            contentId = item.optString("contentId"),
                            category = item.optString("category"),
                            type = item.optString("type"),
                            version = item.optString("version"),
                            size = item.optLong("size"),
                            digest = item.optString("digest"),
                            digestMatches = item.optBoolean("digestMatches"),
                            url = item.optString("url"),
                            sourceUrl = item.optString("sourceUrl"),
                            fileName = item.optString("fileName"),
                            iconFile = item.optString("iconFile")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ========================================================
    // SALVAMENTO (DISCO + ATUALIZAÇÃO DA CACHE)
    // ========================================================

    private fun saveItems(
        items: List<CatalogItem>
    ) {
        val array = JSONArray()

        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("catalogIndex", item.catalogIndex)
                    .put("title", item.title)
                    .put("contentId", item.contentId)
                    .put("category", item.category)
                    .put("type", item.type)
                    .put("version", item.version)
                    .put("size", item.size)
                    .put("digest", item.digest)
                    .put("digestMatches", item.digestMatches)
                    .put("url", item.url)
                    .put("sourceUrl", item.sourceUrl)
                    .put("fileName", item.fileName)
                    .put("iconFile", item.iconFile)
            )
        }

        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
        memoryCache = items.sortedBy { it.catalogIndex }
    }

    // ========================================================
    // ÍCONE
    // ========================================================

    private fun saveIcon(
        catalogIndex: Int,
        icon: ByteArray?
    ) {
        if (icon == null || icon.isEmpty()) return

        val fileName = iconFileName(catalogIndex)
        iconCache.put(fileName, icon)

        try {
            val directory = File(context.filesDir, ICON_DIR)
            if (!directory.exists()) {
                directory.mkdirs()
            }
            File(directory, fileName).writeBytes(icon)
        } catch (_: Exception) {
        }
    }

    private fun iconFileName(
        catalogIndex: Int
    ): String {
        return "icon_${catalogIndex.toString().padStart(6, '0')}.png"
    }

    // ========================================================
    // VALIDAÇÃO RESILIENTE DE URL (COM SUPORTE A [ e ])
    // ========================================================

    private fun isValidUrl(
        rawUrl: String
    ): Boolean {
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
