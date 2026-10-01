package com.gtstore

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {

    private const val TAG = "GTStoreLog"
    private const val FOLDER_NAME = "logs"
    private const val FILE_NAME = "gtstore_debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            // Diretório público: /storage/emulated/0/Download/logs/
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logsDir = File(downloadsDir, FOLDER_NAME)
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            logFile = File(logsDir, FILE_NAME)
            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
            }

            log("=== INÍCIO DA SESSÃO DO APP ===")
        } catch (e: Exception) {
            // Fallback caso a pasta pública esteja inacessível
            try {
                val fallbackDir = File(context.getExternalFilesDir(null), FOLDER_NAME)
                fallbackDir.mkdirs()
                logFile = File(fallbackDir, FILE_NAME)
                if (!logFile!!.exists()) {
                    logFile!!.createNewFile()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Falha ao criar log: ${ex.message}")
            }
        }
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val formattedLine = "[$timestamp] $message\n"

        // Escreve no console (Logcat)
        Log.d(TAG, message)

        // Escreve no arquivo de texto
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
            logFile?.takeIf { it.exists() }?.readText() ?: "Arquivo de log vazio ou não criado."
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
        return logFile?.absolutePath ?: "Download/logs/$FILE_NAME"
    }
}
