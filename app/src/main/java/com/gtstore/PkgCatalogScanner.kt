package com.gtstore

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Scanner independente do servidor HTTP.
 *
 * IMPORTANTE:
 * - Não modifica HttpServer.
 * - Não modifica PackageInfo.
 * - Não interfere no /pkg/{id}.
 * - Não interpreta nomes de arquivos.
 * - Não consulta fontes externas.
 *
 * A classificação é feita exclusivamente pelo CATEGORY
 * retornado pelo PkgMetaReader.
 */
object PkgCatalogScanner {

    /**
     * Resultado de uma leitura individual.
     *
     * O objeto mantém a URI apenas para permitir que a aplicação
     * posteriormente associe o item à origem, sem alterar o
     * conteúdo extraído do PKG.
     */
    data class SourcePkg(
        val uri: Uri,
        val fileName: String
    )

    /**
     * Resultado da varredura.
     *
     * scanned:
     * quantidade de arquivos .pkg encontrados.
     *
     * cataloged:
     * quantidade de PKGs que conseguiram ser lidos pelo
     * PkgMetaReader.
     *
     * failed:
     * arquivos encontrados mas cujo metadata não pôde ser lido.
     */
    data class Result(
        val items: List<PkgCatalogItem>,
        val scanned: Int,
        val cataloged: Int,
        val failed: Int
    )

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

            items += PkgCatalogItem(
                title = meta.title,
                contentId = meta.contentId,
                category = meta.category,
                type = classify(meta.category),
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
     * Classificação baseada EXCLUSIVAMENTE no CATEGORY
     * fornecido pelo PkgMetaReader.
     */
    private fun classify(category: String): String {
        return when (category.lowercase()) {
            "gd" -> PkgCatalogItem.TYPE_GAME
            "gp" -> PkgCatalogItem.TYPE_UPDATE
            "ac" -> PkgCatalogItem.TYPE_DLC
            else -> PkgCatalogItem.TYPE_OTHER
        }
    }
}
