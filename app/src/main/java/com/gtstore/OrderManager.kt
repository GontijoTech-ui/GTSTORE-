package com.gtstore

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

enum class OrderStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class Order(
    val id: String = "",
    val targetPs4Ip: String = "",
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = 0L,
    val items: List<String> = emptyList(),
    val consoleId: String = ""
)

object OrderManager {

    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private val ordersRef by lazy { database.getReference("orders") }

    private val listeners = mutableListOf<() -> Unit>()
    private var cachedOrders: List<Order> = emptyList()
    private var isListening = false

    /**
     * Inicia a escuta em tempo real da nuvem Firebase.
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        ordersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<Order>()

                for (child in snapshot.children) {
                    val statusStr = child.child("status").getValue(String::class.java) ?: "pending"
                    val status = when (statusStr.lowercase()) {
                        "approved" -> OrderStatus.APPROVED
                        "rejected" -> OrderStatus.REJECTED
                        else -> OrderStatus.PENDING
                    }

                    if (status == OrderStatus.PENDING) {
                        val itemsList = mutableListOf<String>()
                        child.child("items").children.forEach { itemSnap ->
                            itemSnap.getValue(String::class.java)?.let { itemsList.add(it) }
                        }

                        val order = Order(
                            id = child.key ?: "",
                            targetPs4Ip = child.child("ps4Ip").getValue(String::class.java) ?: "",
                            consoleId = child.child("consoleId").getValue(String::class.java) ?: "PS4",
                            status = status,
                            createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L,
                            items = itemsList
                        )
                        list.add(order)
                    }
                }

                cachedOrders = list.reversed()
                notifyListeners()
            }

            override fun onCancelled(error: DatabaseError) {
                // Falha de leitura da base de dados
            }
        })
    }

    fun listPendingOrders(): List<Order> = cachedOrders

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    /**
     * Atualiza o estado na nuvem Firebase e liberta o download no PS4.
     */
    fun updateStatus(orderId: String, newStatus: OrderStatus, onComplete: () -> Unit = {}) {
        val statusStr = when (newStatus) {
            OrderStatus.APPROVED -> "approved"
            OrderStatus.REJECTED -> "rejected"
            OrderStatus.PENDING -> "pending"
        }

        val updates = mapOf(
            "status" to statusStr,
            "approvedAt" to System.currentTimeMillis()
        )

        ordersRef.child(orderId).updateChildren(updates).addOnCompleteListener {
            onComplete()
        }
    }
}
