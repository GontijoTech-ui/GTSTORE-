package com.gtstore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Scanner independente do servidor HTTP.
 *
 * Classifica e trata as variações do mesmo jogo (Game, Update, DLC)
 * com base na categoria e metadados retornados pelo PkgMetaReader.
 */
object PkgCatalogScanner {

    data class SourcePkg(
        val uri: Uri,
        val fileName: String
    )

    data class Result(
        val items: List<PkgCatalogItem>,
        val scanned: Int,
        val cataloged: Int,
        val failed: Int
    ) {
        /**
         * Agrupa os pacotes pelo código CUSA / Title ID.
         * Exemplo: agrupa o jogo base CUSA00123 junto com todos os seus updates e DLCs.
         */
        fun groupByGame(): Map<String, List<PkgCatalogItem>> {
            return items.groupBy { extractTitleId(it.contentId) }
        }
    }

    /**
     * Varre uma árvore SAF inteira.
     */
    fun scan(
        context: Context,
        treeUri: Uri
    ): Result {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Result(
                items = emptyList(),
                scanned = 0,
                cataloged = 0,
                failed = 0
            )

        val files = ArrayList<SourcePkg>()
        collectPkgFiles(root, files)

        val items = ArrayList<PkgCatalogItem>()
        var failed = 0

        for (source in files) {
            val meta = try {
                PkgMetaReader.read(
                    context = context,
                    uri = source.uri
                )
            } catch (_: Exception) {
                null
            }

            if (meta == null) {
                failed++
                continue
            }

            val category = meta.category.trim().lowercase()
            val itemType = classify(category)
            val formattedTitle = formatTitle(meta.title, itemType)

            items += PkgCatalogItem(
                title = formattedTitle,
                contentId = meta.contentId,
                category = meta.category,
                type = itemType,
                icon = meta.icon,
                digest = meta.digest,
                digestMatches = meta.digestMatches
            )
        }

        return Result(
            items = items,
            scanned = files.size,
            cataloged = items.size,
            failed = failed
        )
    }

    /**
     * Procura somente arquivos .pkg.
     */
    private fun collectPkgFiles(
        directory: DocumentFile,
        output: MutableList<SourcePkg>
    ) {
        val children = try {
            directory.listFiles()
        } catch (_: Exception) {
            return
        }

        for (child in children) {
            if (child.isDirectory) {
                collectPkgFiles(child, output)
                continue
            }

            if (!child.isFile) {
                continue
            }

            val name = child.name ?: continue

            if (!name.endsWith(".pkg", ignoreCase = true)) {
                continue
            }

            output += SourcePkg(
                uri = child.uri,
                fileName = name
            )
        }
    }

    /**
     * Classificação completa das categorias oficiais do PS4:
     * - gd, gda: Game (App Digital)
     * - gp, gpe: Update / Patch
     * - ac: DLC / Add-on
     */
    fun classify(category: String): String {
        return when (category.trim().lowercase()) {
            "gd", "gda" -> PkgCatalogItem.TYPE_GAME
            "gp", "gpe" -> PkgCatalogItem.TYPE_UPDATE
            "ac" -> PkgCatalogItem.TYPE_DLC
            else -> PkgCatalogItem.TYPE_OTHER
        }
    }

    /**
     * Garante que Updates e DLCs não fiquem com o mesmo título do jogo base.
     */
    private fun formatTitle(originalTitle: String, type: String): String {
        val title = originalTitle.trim()

        return when (type) {
            PkgCatalogItem.TYPE_UPDATE -> {
                if (title.contains("update", ignoreCase = true) || title.contains("patch", ignoreCase = true)) {
                    title
                } else {
                    "$title [UPDATE]"
                }
            }
            PkgCatalogItem.TYPE_DLC -> {
                if (title.contains("dlc", ignoreCase = true)) {
                    title
                } else {
                    "$title [DLC]"
                }
            }
            else -> title
        }
    }

    /**
     * Extrai o Title ID (ex: CUSA00123) a partir do Content ID padrão:
     * Exemplo: EP0001-CUSA00123_00-0000000000000000 -> CUSA00123
     */
    fun extractTitleId(contentId: String): String {
        if (contentId.isBlank()) return ""
        val parts = contentId.split("-")
        return if (parts.size >= 2) {
            parts[1].substringBefore("_")
        } else {
            contentId
        }
    }
}
