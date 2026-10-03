package com.gtstore

import androidx.compose.runtime.mutableStateListOf

data class RequestedGame(
    val gameKey: String,
    val title: String
)

data class AccessBatchRequest(
    val id: String = "${System.currentTimeMillis()}_${(100..999).random()}",
    val consoleId: String,
    val games: List<RequestedGame>,
    val clientIp: String,
    val requestedAt: Long = System.currentTimeMillis()
)

object AccessManager {
    // Fila observável de solicitações pendentes para o Compose
    val pendingBatches = mutableStateListOf<AccessBatchRequest>()

    // Mapa de acessos concedidos: Chave = "$consoleId:$gameKey", Valor = Timestamp de Expiração
    private val activeGrants = mutableMapOf<String, Long>()

    // Janela de validade: 15 minutos (15 * 60 * 1000 ms)
    private const val EXPIRATION_MS = 15 * 60 * 1000L

    @Synchronized
    fun addBatchRequest(batch: AccessBatchRequest) {
        // Remove solicitações idênticas prévias para não poluir
        pendingBatches.removeAll { it.consoleId == batch.consoleId }
        pendingBatches.add(0, batch)
        AppLogger.log("[AccessManager] Novo pacote de ${batch.games.size} jogos recebido de ${batch.consoleId}")
    }

    @Synchronized
    fun approveBatch(batchId: String) {
        val batch = pendingBatches.find { it.id == batchId } ?: return
        val expiration = System.currentTimeMillis() + EXPIRATION_MS

        batch.games.forEach { game ->
            val key = "${batch.consoleId}:${game.gameKey}"
            activeGrants[key] = expiration
        }

        pendingBatches.remove(batch)
        AppLogger.log("[AccessManager] Pacote ${batch.id} APROVADO para ${batch.consoleId} (válido por 15 min)")
    }

    @Synchronized
    fun rejectBatch(batchId: String) {
        pendingBatches.removeAll { it.id == batchId }
        AppLogger.log("[AccessManager] Pacote $batchId RECUSADO")
    }

    @Synchronized
    fun isAccessApproved(consoleId: String, gameKey: String): Boolean {
        val key = "$consoleId:$gameKey"
        val expiresAt = activeGrants[key] ?: return false

        return if (System.currentTimeMillis() <= expiresAt) {
            true
        } else {
            activeGrants.remove(key)
            false
        }
    }

    @Synchronized
    fun areAllGamesApproved(consoleId: String, gameKeys: List<String>): Boolean {
        if (gameKeys.isEmpty()) return false
        return gameKeys.all { isAccessApproved(consoleId, it) }
    }
}
