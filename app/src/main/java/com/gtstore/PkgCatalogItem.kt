package com.gtstore

/**
 * Representa somente os dados catalogados de um PKG.
 *
 * Não participa da transferência HTTP.
 * Não altera PackageInfo.
 * Não altera /pkg/{id}.
 */
data class PkgCatalogItem(
    val title: String,
    val contentId: String,
    val category: String,
    val type: String,
    val icon: ByteArray?,
    val digest: String,
    val digestMatches: Boolean
) {
    val isGame: Boolean
        get() = type == TYPE_GAME

    val isUpdate: Boolean
        get() = type == TYPE_UPDATE

    val isDlc: Boolean
        get() = type == TYPE_DLC

    val isOther: Boolean
        get() = type == TYPE_OTHER

    companion object {
        const val TYPE_GAME = "GAME"
        const val TYPE_UPDATE = "UPDATE"
        const val TYPE_DLC = "DLC"
        const val TYPE_OTHER = "OTHER"
    }
}
