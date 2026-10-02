package com.gtstore

import android.content.Context
import android.os.Environment
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

        AppLogger.log("[CatalogManager] PKG lido com sucesso. Gravando ou atualizando no catálogo...")
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
        AppLogger.log("[CatalogManager] sourceUrl (Página de Origem): \"$normalizedSource\"")
        AppLogger.log("[CatalogManager] directUrl (Download PKG): \"$normalizedDirect\"")
        AppLogger.log("[CatalogManager] fileName: \"$normalizedFileName\"")

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

        AppLogger.log("[CatalogManager] PKG reconhecido. Atualizando link, origem e capa do item...")
        return saveRemotePkg(
            remote = remote,
            sourceUrl = normalizedSource,
            fileName = normalizedFileName
        )
    }

    // ========================================================
    // SALVAMENTO / ATUALIZAÇÃO CENTRAL
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
            val finalSourceUrl = if (sourceUrl.isNotBlank()) {
                sourceUrl.trim()
            } else {
                existing.sourceUrl
            }

            val finalFileName = if (fileName.isNotBlank()) {
                fileName.trim()
            } else {
                existing.fileName
            }

            val iconName = if (existing.iconFile.isNotBlank()) {
                existing.iconFile
            } else {
                iconFileName(existing.catalogIndex)
            }

            AppLogger.log("[CatalogManager] Atualizando item: ${existing.title} (Índice: ${existing.indexString})")
            AppLogger.log("[CatalogManager] -> Nova página de origem: \"$finalSourceUrl\"")
            AppLogger.log("[CatalogManager] -> Novo link direto: \"${remote.url}\"")

            val updated =
                existing.copy(
                    url = remote.url,
                    sourceUrl = finalSourceUrl,
                    fileName = finalFileName,
                    size = remote.size,
                    digest = remote.digest,
                    digestMatches = remote.digestMatches,
                    iconFile = iconName
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

            if (remote.icon != null && remote.icon.isNotEmpty()) {
                saveIcon(existing.catalogIndex, remote.icon)
                AppLogger.log("[CatalogManager] Capa regravada fisicamente com sucesso para índice ${existing.indexString}")
            }

            return OperationResult(
                true,
                "Link, página de origem e capa atualizados com sucesso para [${updated.type}] ${updated.title}.",
                updated
            )
        }

        val newIndex = nextIndex()
        val itemType = classify(remote.category)
        val formattedTitle =
            formatItemTitle(
                remote.title,
                itemType,
                remote.version
            )

        AppLogger.log("[CatalogManager] Criando novo registro: $formattedTitle (Novo Índice: $newIndex)")

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
                sourceUrl = sourceUrl.trim(),
                fileName = fileName.trim(),
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
    // BACKUP E RESTAURAÇÃO (JSON SIMPLES)
    // ========================================================

    fun exportCatalogBackup(): String {
        return try {
            val rawItems = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
            val nextIdx = prefs.getInt(KEY_NEXT_INDEX, 1)

            val backupObject = JSONObject().apply {
                put("version", 1)
                put("next_index", nextIdx)
                put("items", JSONArray(rawItems))
            }

            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "logs")
            if (!dir.exists()) dir.mkdirs()

            val backupFile = File(dir, "gtstore_catalog_backup.json")
            backupFile.writeText(backupObject.toString(2))

            AppLogger.log("[CatalogManager] Backup exportado para: ${backupFile.absolutePath}")
            "Backup salvo em: Download/logs/gtstore_catalog_backup.json"
        } catch (e: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(null), "logs")
            val fallbackFile = File(fallbackDir, "gtstore_catalog_backup.json")
            try {
                val rawItems = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
                fallbackFile.writeText(rawItems)
                "Backup salvo em: ${fallbackFile.absolutePath}"
            } catch (_: Exception) {
                "Erro ao exportar backup: ${e.message}"
            }
        }
    }

    fun importCatalogBackup(): String {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val candidateFiles = listOf(
                File(File(downloadDir, "logs"), "gtstore_catalog_backup.json"),
                File(downloadDir, "gtstore_catalog_backup.json"),
                File(File(context.getExternalFilesDir(null), "logs"), "gtstore_catalog_backup.json")
            )

            val fileToRead = candidateFiles.firstOrNull { it.exists() && it.length() > 0L }
                ?: return "Arquivo de backup não encontrado (gtstore_catalog_backup.json)."

            val content = fileToRead.readText().trim()
            val itemsArray: JSONArray
            val nextIdx: Int

            if (content.startsWith("{")) {
                val backupObject = JSONObject(content)
                nextIdx = backupObject.optInt("next_index", 1)
                itemsArray = backupObject.optJSONArray("items") ?: JSONArray()
            } else if (content.startsWith("[")) {
                itemsArray = JSONArray(content)
                nextIdx = itemsArray.length() + 1
            } else {
                return "Formato de arquivo JSON inválido."
            }

            prefs.edit()
                .putString(KEY_ITEMS, itemsArray.toString())
                .putInt(KEY_NEXT_INDEX, nextIdx)
                .apply()

            memoryCache = null
            getAll()

            AppLogger.log("[CatalogManager] Backup restaurado de: ${fileToRead.absolutePath}")
            "Catálogo restaurado! (${itemsArray.length()} itens recuperados)"
        } catch (e: Exception) {
            AppLogger.log("[CatalogManager] Erro ao restaurar backup: ${e.message}")
            "Erro ao restaurar backup: ${e.message}"
        }
    }

    // ========================================================
    // BACKUP E RESTAURAÇÃO COMPLETO EM ZIP (JSON + ÍCONES)
    // ========================================================

    fun exportCatalogZipBackup(): String {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logsDir = File(downloadDir, "logs")
            if (!logsDir.exists()) logsDir.mkdirs()

            val zipFile = File(logsDir, "gtstore_complete_backup.zip")

            val rawItems = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
            val nextIdx = prefs.getInt(KEY_NEXT_INDEX, 1)
            val backupObject = JSONObject().apply {
                put("version", 1)
                put("next_index", nextIdx)
                put("items", JSONArray(rawItems))
            }

            val iconsDir = File(context.filesDir, ICON_DIR)
            val iconFiles = iconsDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val jsonEntry = ZipEntry("gtstore_catalog_backup.json")
                zos.putNextEntry(jsonEntry)
                zos.write(backupObject.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val buffer = ByteArray(8192)
                for (icon in iconFiles) {
                    val entry = ZipEntry("$ICON_DIR/${icon.name}")
                    zos.putNextEntry(entry)
                    FileInputStream(icon).use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }

            AppLogger.log("[CatalogManager] Backup ZIP criado em: ${zipFile.absolutePath} com ${iconFiles.size} capas.")
            "Backup ZIP salvo em: Download/logs/gtstore_complete_backup.zip (${iconFiles.size} ícones)"
        } catch (e: Exception) {
            AppLogger.log("[CatalogManager] Erro ao exportar ZIP: ${e.message}")
            "Erro ao exportar ZIP: ${e.message}"
        }
    }

    fun importCatalogZipBackup(): String {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val candidateZip = listOf(
                File(File(downloadDir, "logs"), "gtstore_complete_backup.zip"),
                File(downloadDir, "gtstore_complete_backup.zip")
            ).firstOrNull { it.exists() && it.length() > 0L }
                ?: return "Arquivo gtstore_complete_backup.zip não encontrado em Download/logs/."

            val iconsDir = File(context.filesDir, ICON_DIR)
            if (!iconsDir.exists()) iconsDir.mkdirs()

            var jsonString: String? = null
            var totalIconsRestored = 0
            val buffer = ByteArray(8192)

            ZipInputStream(FileInputStream(candidateZip)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    if (entryName.endsWith(".json")) {
                        jsonString = zis.readBytes().toString(Charsets.UTF_8)
                    } else if (entryName.startsWith("$ICON_DIR/") || entryName.endsWith(".png")) {
                        val fileName = File(entryName).name
                        if (fileName.isNotBlank()) {
                            val outFile = File(iconsDir, fileName)
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                            if (outFile.exists() && outFile.length() > 0L) {
                                try {
                                    iconCache.put(fileName, outFile.readBytes())
                                } catch (_: Exception) {}
                                totalIconsRestored++
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (jsonString.isNullOrBlank()) {
                return "Erro: O ZIP não possui o arquivo de catálogo JSON."
            }

            val backupObject = JSONObject(jsonString)
            val nextIdx = backupObject.optInt("next_index", 1)
            val itemsArray = backupObject.optJSONArray("items") ?: JSONArray()

            prefs.edit()
                .putString(KEY_ITEMS, itemsArray.toString())
                .putInt(KEY_NEXT_INDEX, nextIdx)
                .apply()

            memoryCache = null
            getAll()

            AppLogger.log("[CatalogManager] ZIP Restaurado: ${itemsArray.length()} itens e $totalIconsRestored capas.")
            "Sucesso! ${itemsArray.length()} jogos e $totalIconsRestored capas restaurados do ZIP."
        } catch (e: Exception) {
            AppLogger.log("[CatalogManager] Erro ao importar ZIP: ${e.message}")
            "Erro ao restaurar ZIP: ${e.message}"
        }
    }

    // ========================================================
    // CONSULTAS COM CACHE
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
        return getAll().firstOrNull { it.catalogIndex == catalogIndex }
    }

    fun getByContentId(
        contentId: String
    ): CatalogItem? {
        return getAll().firstOrNull { it.contentId.equals(contentId, ignoreCase = true) }
    }

    // ========================================================
    // ÍCONES E RECUPERAÇÃO EM MASSA
    // ========================================================

    fun getIcon(
        item: CatalogItem
    ): ByteArray? {
        if (item.iconFile.isBlank()) return null
        iconCache.get(item.iconFile)?.let { return it }

        val file = File(context.filesDir, "$ICON_DIR/${item.iconFile}")
        return try {
            if (file.exists() && file.length() > 0L) {
                val bytes = file.readBytes()
                iconCache.put(item.iconFile, bytes)
                bytes
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun restaurarIconesFaltantes(
        onProgress: (atual: Int, total: Int, itemNome: String) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, ICON_DIR)
        if (!directory.exists()) directory.mkdirs()

        val items = getAll()
        val pendentes = items.filter { item ->
            val iconFile = File(directory, item.iconFile)
            !iconFile.exists() || iconFile.length() == 0L
        }

        val total = pendentes.size
        var recuperados = 0

        AppLogger.log("[CatalogManager] Iniciando recuperação de $total ícones via links diretos de PKG...")

        pendentes.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) {
                onProgress(index + 1, total, item.title)
            }

            val iconFile = File(directory, item.iconFile)
            var sucesso = false

            if (item.url.isNotBlank()) {
                try {
                    val remote = RemotePkgReader.read(context, item.url)
                    if (remote?.icon != null && remote.icon.isNotEmpty()) {
                        iconFile.writeBytes(remote.icon)
                        iconCache.put(item.iconFile, remote.icon)
                        sucesso = true
                        AppLogger.log("[CatalogManager] Capa baixada com sucesso do PKG: ${item.title}")
                    }
                } catch (e: Exception) {
                    AppLogger.log("[CatalogManager] Falha ao extrair capa de ${item.title}: ${e.message}")
                }
            }

            if (sucesso) {
                recuperados++
            }
        }

        AppLogger.log("[CatalogManager] Recuperação concluída: $recuperados/$total capas salvas.")
        recuperados
    }

    // ========================================================
    // FORMATAÇÃO E REGRAS
    // ========================================================

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
                if (cleanTitle.contains("dlc", ignoreCase = true)) cleanTitle else "$cleanTitle [DLC]"
            }
            else -> cleanTitle
        }
    }

    private fun normalizeVersion(v: String?): String {
        return v?.trim()?.removePrefix("0")?.ifBlank { "0" } ?: "0"
    }

    private fun classify(category: String): String {
        return when (category.trim().lowercase()) {
            "gd", "gda" -> PkgCatalogItem.TYPE_GAME
            "gp", "gpe" -> PkgCatalogItem.TYPE_UPDATE
            "ac" -> PkgCatalogItem.TYPE_DLC
            else -> PkgCatalogItem.TYPE_OTHER
        }
    }

    private fun nextIndex(): Int {
        val current = prefs.getInt(KEY_NEXT_INDEX, 1)
        prefs.edit().putInt(KEY_NEXT_INDEX, current + 1).apply()
        return current
    }

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

    private fun saveItems(items: List<CatalogItem>) {
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

    private fun saveIcon(catalogIndex: Int, icon: ByteArray?) {
        if (icon == null || icon.isEmpty()) return
        val fileName = iconFileName(catalogIndex)
        
        iconCache.put(fileName, icon)
        try {
            val directory = File(context.filesDir, ICON_DIR)
            if (!directory.exists()) directory.mkdirs()
            File(directory, fileName).writeBytes(icon)
        } catch (_: Exception) {
        }
    }

    private fun iconFileName(catalogIndex: Int): String {
        return "icon_${catalogIndex.toString().padStart(6, '0')}.png"
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
