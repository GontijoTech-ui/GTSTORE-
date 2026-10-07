package com.gtstore

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
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
     * Sincroniza a lista de jogos locais diretamente no nó /packages do Firebase.
     */
    fun syncCatalogToFirebase(items: List<CatalogItem>) {
        try {
            val packagesRef = database.getReference("packages")
            val catalogPayload = items.map { item ->
                mapOf(
                    "id" to item.catalogIndex,
                    "catalogIndex" to item.catalogIndex,
                    "index" to item.indexString,
                    "title" to item.title,
                    "fileName" to item.fileName,
                    "file" to "${item.indexString}.pkg",
                    "size" to item.size,
                    "version" to item.version,
                    "category" to item.category,
                    "type" to item.type,
                    "contentId" to item.contentId,
                    "digest" to item.digest,
                    "url" to item.url
                )
            }

            packagesRef.setValue(catalogPayload)
                .addOnSuccessListener {
                    Log.i(TAG, "Catálogo sincronizado no Firebase com sucesso (${items.size} itens).")
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
