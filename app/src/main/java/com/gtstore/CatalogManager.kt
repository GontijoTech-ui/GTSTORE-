package com.gtstore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

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
    }

    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun registerOrUpdate(
        rawUrl: String
    ): OperationResult {

        val url = rawUrl.trim()

        if (!isValidUrl(url)) {
            return OperationResult(
                false,
                "URL inválida. Use http:// ou https://."
            )
        }

        val remote =
            RemotePkgReader.read(
                context,
                url
            )
                ?: return OperationResult(
                    false,
                    "Não foi possível reconhecer um PKG nessa URL."
                )

        val items = loadItems()

        /*
         * A identidade de um pacote no catálogo é composta por:
         * CONTENT_ID + CATEGORIA + VERSÃO
         *
         * Isso permite que o Jogo Base (gd), o Update (gp v01.01) e as DLCs (ac)
         * coexistam no catálogo sem conflitar nem sobrescrever um ao outro.
         */
        val existing =
            items.firstOrNull {
                it.contentId.equals(remote.contentId, ignoreCase = true) &&
                it.category.equals(remote.category, ignoreCase = true) &&
                normalizeVersion(it.version) == normalizeVersion(remote.version)
            }

        if (existing != null) {
            /*
             * Mesma variação já existente: apenas atualiza o link (URL)
             * preservando o catalogIndex original.
             */
            val updated =
                existing.copy(
                    url = remote.url,
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

            saveIcon(
                updated.catalogIndex,
                remote.icon
            )

            return OperationResult(
                true,
                "URL atualizada para [${updated.type}] ${updated.title}. Índice ${existing.indexString} preservado.",
                updated
            )
        }

        val newIndex = nextIndex()
        val itemType = classify(remote.category)
        val formattedTitle = formatItemTitle(remote.title, itemType, remote.version)

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
                url = remote.url
            )

        saveIcon(
            newIndex,
            remote.icon
        )

        val finalItem =
            item.copy(
                iconFile = iconFileName(newIndex)
            )

        saveItems(
            items + finalItem
        )

        return OperationResult(
            true,
            "PKG reconhecido como [$itemType] e adicionado com o índice ${finalItem.indexString}.",
            finalItem
        )
    }

    fun getAll(): List<CatalogItem> {
        return loadItems()
            .sortedBy { it.catalogIndex }
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

    fun getIcon(
        item: CatalogItem
    ): ByteArray? {

        if (item.iconFile.isBlank()) {
            return null
        }

        val file =
            File(
                context.filesDir,
                "$ICON_DIR/${item.iconFile}"
            )

        return try {
            if (file.exists()) {
                file.readBytes()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Adiciona indicação clara no título caso seja Update ou DLC
     * para que o usuário diferencie visualmente no console e no app.
     */
    private fun formatItemTitle(
        originalTitle: String,
        type: String,
        version: String
    ): String {
        val cleanTitle = originalTitle.trim()

        return when (type) {
            PkgCatalogItem.TYPE_UPDATE -> {
                val v = if (version.isNotBlank()) " v$version" else ""
                if (cleanTitle.contains("update", ignoreCase = true) || cleanTitle.contains("patch", ignoreCase = true)) {
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

    private fun normalizeVersion(v: String?): String {
        return v?.trim()?.removePrefix("0")?.ifBlank { "0" } ?: "0"
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

    private fun nextIndex(): Int {
        val current = prefs.getInt(KEY_NEXT_INDEX, 1)

        prefs.edit()
            .putInt(KEY_NEXT_INDEX, current + 1)
            .apply()

        return current
    }

    private fun loadItems(): List<CatalogItem> {
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
                            iconFile = item.optString("iconFile")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

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
                    .put("iconFile", item.iconFile)
            )
        }

        prefs.edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun saveIcon(
        catalogIndex: Int,
        icon: ByteArray?
    ) {
        if (icon == null || icon.isEmpty()) return

        try {
            val directory = File(context.filesDir, ICON_DIR)
            if (!directory.exists()) {
                directory.mkdirs()
            }
            File(directory, iconFileName(catalogIndex)).writeBytes(icon)
        } catch (_: Exception) {
        }
    }

    private fun iconFileName(catalogIndex: Int): String {
        return "icon_${catalogIndex.toString().padStart(6, '0')}.png"
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
