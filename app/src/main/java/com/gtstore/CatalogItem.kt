package com.gtstore

data class CatalogItem(
    val catalogIndex: Int,
    val title: String,
    val contentId: String,
    val category: String,
    val type: String,
    val version: String,
    val size: Long,
    val digest: String,
    val digestMatches: Boolean,
    val url: String,

    // URL original do site onde o PKG foi encontrado.
    // Usada pelo botão ATUALIZAR.
    val sourceUrl: String = "",

    // Nome do arquivo descoberto durante a captura.
    val fileName: String = "",

    val iconFile: String = ""
) {
    val indexString: String
        get() = catalogIndex
            .toString()
            .padStart(6, '0')
}
