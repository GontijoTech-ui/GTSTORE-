package com.gtstore

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "GTStoreLog"
    private const val FILE_NAME = "gtstore_debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            // Usa o diretório de dados do app (compatível com Android 10, 11, 12, 13, 14, 15)
            // Não precisa de permissão de escrita e nunca dá Permission Denied (EACCES)
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val logsDir = File(baseDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            logFile = File(logsDir, FILE_NAME)
            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
            }

            log("=== INÍCIO DA SESSÃO DO APP ===")
        } catch (e: Exception) {
            Log.e(TAG, "Falha crítica ao iniciar arquivo de log: ${e.message}")
        }
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLine = "[$timestamp] $message\n"

        // Escreve no Logcat
        Log.d(TAG, message)

        // Escreve no arquivo de texto local
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.append(formattedLine)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun getLogContent(): String {
        return try {
            logFile?.takeIf { it.exists() }?.readText() ?: "Arquivo de log ainda não possui dados."
        } catch (e: Exception) {
            "Erro ao ler log: ${e.message}"
        }
    }

    fun clearLog() {
        try {
            logFile?.writeText("")
            log("=== LOG REINICIADO ===")
        } catch (_: Exception) {
        }
    }

    fun getLogPath(): String {
        return logFile?.absolutePath ?: "Indisponível"
    }
}
