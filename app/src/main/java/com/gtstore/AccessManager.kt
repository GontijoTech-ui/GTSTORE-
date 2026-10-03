package com.gtstore

import androidx.compose.runtime.mutableStateListOf

data class AccessRequestItem(
    val consoleId: String,
    val gameKey: String,
    val gameTitle: String,
    val clientIp: String,
    val requestedAt: Long = System.currentTimeMillis()
)

object AccessManager {
    // Lista observável pelo Jetpack Compose para atualizar o painel na hora
    val pendingRequests = mutableStateListOf<AccessRequestItem>()

    // Mapa de aprovações ativas: chave "consoleId:gameKey" -> expiração em milissegundos
    private val activeGrants = mutableMapOf<String, Long>()

    // Janela de validade: 10 minutos
    private const val EXPIRATION_MS = 10 * 60 * 1000L

    @Synchronized
    fun addRequest(req: AccessRequestItem) {
        pendingRequests.removeAll { it.consoleId == req.consoleId && it.gameKey == req.gameKey }
        pendingRequests.add(0, req)
        AppLogger.log("[AccessManager] Nova solicitação: ${req.gameTitle} (${req.gameKey}) por ${req.consoleId}")
    }

    @Synchronized
    fun approveAccess(consoleId: String, gameKey: String) {
        val key = "$consoleId:$gameKey"
        activeGrants[key] = System.currentTimeMillis() + EXPIRATION_MS
        pendingRequests.removeAll { it.consoleId == consoleId && it.gameKey == gameKey }
        AppLogger.log("[AccessManager] Acesso APROVADO para $key (válido por 10 min)")
    }

    @Synchronized
    fun rejectAccess(consoleId: String, gameKey: String) {
        pendingRequests.removeAll { it.consoleId == consoleId && it.gameKey == gameKey }
        AppLogger.log("[AccessManager] Solicitação RECUSADA para $consoleId:$gameKey")
    }

    @Synchronized
    fun isAccessApproved(consoleId: String, gameKey: String): Boolean {
        val key = "$consoleId:$gameKey"
        val expiresAt = activeGrants[key] ?: return false

        return if (System.currentTimeMillis() <= expiresAt) {
            true
        } else {
            activeGrants.remove(key)
            AppLogger.log("[AccessManager] Acesso expirou para $key")
            false
        }
    }
}
