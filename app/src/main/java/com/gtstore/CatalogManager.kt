package com.gtstore

import android.content.Context
import android.util.Base64
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
         * A identidade principal é o CONTENT_ID.
         *
         * A URL não participa da identidade.
         */
        val existing =
            items.firstOrNull {
                it.contentId.equals(
                    remote.contentId,
                    ignoreCase = true
                )
            }

        if (existing != null) {

            if (!identityMatches(existing, remote)) {
                return OperationResult(
                    false,
                    buildMismatchMessage(
                        existing,
                        remote
                    ),
                    existing
                )
            }

            /*
             * IMPORTANTE:
             * em atualização de URL, o índice permanece
             * exatamente o mesmo.
             */
            val updated =
                existing.copy(
                    url = remote.url
                )

            val updatedItems =
                items.map {
                    if (it.catalogIndex ==
                        existing.catalogIndex
                    ) {
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
                "URL atualizada. Índice ${existing.indexString} preservado.",
                updated
            )
        }

        val newIndex =
            nextIndex()

        val item =
            CatalogItem(
                catalogIndex = newIndex,
                title = remote.title,
                contentId = remote.contentId,
                category = remote.category,
                type = classify(remote.category),
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
                iconFile =
                    iconFileName(newIndex)
            )

        saveItems(
            items + finalItem
        )

        return OperationResult(
            true,
            "PKG reconhecido e adicionado ao catálogo como índice ${finalItem.indexString}.",
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

    private fun identityMatches(
        existing: CatalogItem,
        remote: RemotePkgReader.Result
    ): Boolean {

        /*
         * CONTENT_ID é a identidade principal.
         */
        if (!existing.contentId.equals(
                remote.contentId,
                ignoreCase = true
            )
        ) {
            return false
        }

        /*
         * CATEGORY evita trocar, por exemplo,
         * GAME por UPDATE/DLC com o mesmo identificador
         * em uma situação inconsistente.
         */
        if (!existing.category.equals(
                remote.category,
                ignoreCase = true
            )
        ) {
            return false
        }

        /*
         * TITLE funciona como confirmação adicional.
         */
        if (!sameTitle(
                existing.title,
                remote.title
            )
        ) {
            return false
        }

        return true
    }

    private fun sameTitle(
        first: String,
        second: String
    ): Boolean {

        fun normalize(value: String): String =
            value
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .lowercase()

        return normalize(first) ==
                normalize(second)
    }

    private fun buildMismatchMessage(
        existing: CatalogItem,
        remote: RemotePkgReader.Result
    ): String {

        return buildString {
            append("URL rejeitada.\n")
            append(
                "Esperado: ${existing.contentId}"
            )
            append(" / ${existing.category}\n")
            append(
                "Encontrado: ${remote.contentId}"
            )
            append(" / ${remote.category}\n")
            append(
                "Índice ${existing.indexString} e URL atual preservados."
            )
        }
    }

    private fun classify(
        category: String
    ): String {

        return when (
            category.trim().lowercase()
        ) {
            "gd" -> PkgCatalogItem.TYPE_GAME
            "gp" -> PkgCatalogItem.TYPE_UPDATE
            "ac" -> PkgCatalogItem.TYPE_DLC
            else -> PkgCatalogItem.TYPE_OTHER
        }
    }

    private fun nextIndex(): Int {

        val current =
            prefs.getInt(
                KEY_NEXT_INDEX,
                1
            )

        /*
         * O contador nunca diminui.
         *
         * Assim um índice utilizado não é reutilizado
         * caso o item seja removido futuramente.
         */
        prefs.edit()
            .putInt(
                KEY_NEXT_INDEX,
                current + 1
            )
            .apply()

        return current
    }

    private fun loadItems(): List<CatalogItem> {

        val raw =
            prefs.getString(
                KEY_ITEMS,
                null
            )
                ?: return emptyList()

        return try {

            val array =
                JSONArray(raw)

            buildList {

                for (i in 0 until array.length()) {

                    val item =
                        array.getJSONObject(i)

                    add(
                        CatalogItem(
                            catalogIndex =
                                item.getInt(
                                    "catalogIndex"
                                ),
                            title =
                                item.optString(
                                    "title"
                                ),
                            contentId =
                                item.optString(
                                    "contentId"
                                ),
                            category =
                                item.optString(
                                    "category"
                                ),
                            type =
                                item.optString(
                                    "type"
                                ),
                            version =
                                item.optString(
                                    "version"
                                ),
                            size =
                                item.optLong(
                                    "size"
                                ),
                            digest =
                                item.optString(
                                    "digest"
                                ),
                            digestMatches =
                                item.optBoolean(
                                    "digestMatches"
                                ),
                            url =
                                item.optString(
                                    "url"
                                ),
                            iconFile =
                                item.optString(
                                    "iconFile"
                                )
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
                    .put(
                        "catalogIndex",
                        item.catalogIndex
                    )
                    .put(
                        "title",
                        item.title
                    )
                    .put(
                        "contentId",
                        item.contentId
                    )
                    .put(
                        "category",
                        item.category
                    )
                    .put(
                        "type",
                        item.type
                    )
                    .put(
                        "version",
                        item.version
                    )
                    .put(
                        "size",
                        item.size
                    )
                    .put(
                        "digest",
                        item.digest
                    )
                    .put(
                        "digestMatches",
                        item.digestMatches
                    )
                    .put(
                        "url",
                        item.url
                    )
                    .put(
                        "iconFile",
                        item.iconFile
                    )
            )
        }

        prefs.edit()
            .putString(
                KEY_ITEMS,
                array.toString()
            )
            .apply()
    }

    private fun saveIcon(
        catalogIndex: Int,
        icon: ByteArray?
    ) {

        if (icon == null ||
            icon.isEmpty()
        ) {
            return
        }

        try {

            val directory =
                File(
                    context.filesDir,
                    ICON_DIR
                )

            if (!directory.exists()) {
                directory.mkdirs()
            }

            File(
                directory,
                iconFileName(catalogIndex)
            ).writeBytes(icon)

        } catch (_: Exception) {
        }
    }

    private fun iconFileName(
        catalogIndex: Int
    ): String {
        return "icon_${catalogIndex.toString().padStart(6, '0')}.png"
    }

    private fun isValidUrl(
        rawUrl: String
    ): Boolean {

        return try {

            val uri =
                URI(rawUrl)

            val scheme =
                uri.scheme?.lowercase()

            (scheme == "http" ||
                    scheme == "https") &&
                    !uri.host.isNullOrBlank()

        } catch (_: Exception) {
            false
        }
    }
}
