package com.gtstore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Estados do ciclo de vida das solicitações de checkout.
 */
enum class OrderStatus {
    PENDING,
    APPROVED,
    REJECTED
}

/**
 * Representação de um pedido de checkout (compatível com nuvem e HTTP local).
 */
data class Order(
    val id: String = "",
    val items: List<String> = emptyList(),
    var targetPs4Ip: String = "",
    var status: OrderStatus = OrderStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val consoleId: String = ""
)

/**
 * Gestor thread-safe que sincroniza em tempo real com o Firebase Realtime Database
 * e atende às requisições do HttpServer e do MainActivity.
 */
object OrderManager {
    private const val TAG = "OrderManager"
    private val orders = ConcurrentHashMap<String, Order>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private val ordersRef by lazy { database.getReference("orders") }

    private var isListening = false

    /**
     * Inicia a escuta em tempo real dos pedidos na nuvem Firebase.
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        try {
            ordersRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newMap = mutableMapOf<String, Order>()

                    for (child in snapshot.children) {
                        val key = child.key ?: continue
                        val statusStr = child.child("status").getValue(String::class.java) ?: "pending"
                        val status = when (statusStr.lowercase()) {
                            "approved" -> OrderStatus.APPROVED
                            "rejected" -> OrderStatus.REJECTED
                            else -> OrderStatus.PENDING
                        }

                        val itemsList = mutableListOf<String>()
                        child.child("items").children.forEach { itemSnap ->
                            itemSnap.getValue(String::class.java)?.let { itemsList.add(it) }
                        }

                        val order = Order(
                            id = key,
                            items = itemsList,
                            targetPs4Ip = child.child("ps4Ip").getValue(String::class.java) ?: "",
                            status = status,
                            timestamp = child.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
                            consoleId = child.child("consoleId").getValue(String::class.java) ?: "PS4"
                        )
                        newMap[key] = order
                    }

                    orders.clear()
                    orders.putAll(newMap)
                    notifyListeners()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Falha na sincronização do Firebase: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar escuta do Firebase: ${e.message}")
        }
    }

    /**
     * SOLUÇÃO DEFINITIVA DE SINCRONIZAÇÃO:
     * Grava como MAPA chaveado pelo catalogIndex (ex: /packages/1, /packages/2),
     * garantindo integridade de tipos (size: Long) e campos canônicos (url, cusa, digest).
     */
    fun syncCatalogToFirebase(items: List<CatalogItem>, catalogManager: CatalogManager) {
        try {
            val packagesRef = database.getReference("packages")

            // Cria um mapa indexado por ID em vez de uma lista/array
            val catalogMap = HashMap<String, Any>()

            items.forEach { item ->
                val key = item.catalogIndex.toString()

                // Gera thumbnail leve em Base64
                val iconBytes = catalogManager.getIcon(item)
                val iconBase64 = if (iconBytes != null && iconBytes.isNotEmpty()) {
                    try {
                        val bmp = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                        if (bmp != null) {
                            val targetW = 260
                            val targetH = (bmp.height.toFloat() / bmp.width * targetW).toInt().coerceAtLeast(1)
                            val scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                            val stream = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        } else {
                            "data:image/png;base64," + Base64.encodeToString(iconBytes, Base64.NO_WRAP)
                        }
                    } catch (_: Exception) {
                        "data:image/png;base64," + Base64.encodeToString(iconBytes, Base64.NO_WRAP)
                    }
                } else {
                    ""
                }

                // Extrai CUSA de forma consistente
                val cusaRegex = Regex("CUSA\\d{5}", RegexOption.IGNORE_CASE)
                val extractedCusa = cusaRegex.find(item.contentId)?.value?.uppercase(Locale.ROOT)
                    ?: cusaRegex.find(item.fileName)?.value?.uppercase(Locale.ROOT)
                    ?: ""

                // Garante que o digest tenha 64 caracteres
                val validDigest = if (item.digest.isNotBlank() && item.digest.length == 64) {
                    item.digest.uppercase(Locale.ROOT)
                } else {
                    "0".repeat(64)
                }

                val packageData = mapOf(
                    "catalogIndex" to item.catalogIndex,
                    "id" to item.catalogIndex,
                    "index" to item.indexString,
                    "title" to item.title.trim(),
                    "contentId" to item.contentId.trim().uppercase(Locale.ROOT),
                    "cusa" to extractedCusa,
                    "category" to item.category.trim().lowercase(Locale.ROOT),
                    "type" to item.type.trim().uppercase(Locale.ROOT),
                    "version" to item.version.ifBlank { "01.00" },
                    "size" to item.size, // Mantém Long em bytes
                    "url" to item.url.trim(),
                    "digest" to validDigest,
                    "fileName" to item.fileName,
                    "sourceUrl" to item.sourceUrl,
                    "iconUrl" to iconBase64,
                    "price" to 10.0
                )

                catalogMap[key] = packageData
            }

            // Grava o mapa completo: o Firebase cria /packages/1, /packages/2, etc.
            packagesRef.setValue(catalogMap)
                .addOnSuccessListener {
                    Log.i(TAG, "Catálogo sincronizado no Firebase com sucesso (${items.size} itens sob chaves diretas).")
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Erro ao enviar catálogo para o Firebase: ${error.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro na rotina de sincronização do catálogo: ${e.message}")
        }
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao notificar ouvinte: ${e.message}")
            }
        }
    }

    fun createOrder(items: List<String>, targetIp: String): Order {
        val id = UUID.randomUUID().toString().substring(0, 8).uppercase()
        val order = Order(id = id, items = items, targetPs4Ip = targetIp)
        orders[id] = order
        notifyListeners()

        try {
            val payload = mapOf(
                "consoleId" to "PS4",
                "ps4Ip" to targetIp,
                "status" to "pending",
                "createdAt" to order.timestamp,
                "items" to items
            )
            ordersRef.child(id).setValue(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível gravar pedido local no Firebase: ${e.message}")
        }

        return order
    }

    fun getOrder(id: String): Order? = orders[id]

    fun updateStatus(id: String, status: OrderStatus): Boolean {
        val order = orders[id] ?: Order(id = id)
        order.status = status
        orders[id] = order
        notifyListeners()

        val statusStr = when (status) {
            OrderStatus.APPROVED -> "approved"
            OrderStatus.REJECTED -> "rejected"
            OrderStatus.PENDING -> "pending"
        }

        try {
            val updates = mapOf(
                "status" to statusStr,
                "approvedAt" to System.currentTimeMillis()
            )
            ordersRef.child(id).updateChildren(updates)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível atualizar status no Firebase: ${e.message}")
        }

        return true
    }

    fun listPendingOrders(): List<Order> {
        return orders.values.filter { it.status == OrderStatus.PENDING }.sortedByDescending { it.timestamp }
    }

    fun listAllOrders(): List<Order> {
        return orders.values.sortedByDescending { it.timestamp }
    }
}
