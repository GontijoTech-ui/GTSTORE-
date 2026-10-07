package com.gtstore

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OrderRequest(
    val id: String = "",
    val consoleId: String = "",
    val ps4Ip: String = "",
    val status: String = "pending",
    val createdAt: Long = 0L,
    val items: List<String> = emptyList()
)

object OrderManager {
    private val database = FirebaseDatabase.getInstance()
    private val ordersRef = database.getReference("orders")

    private val _pendingOrders = MutableStateFlow<List<OrderRequest>>(emptyList())
    val pendingOrders: StateFlow<List<OrderRequest>> = _pendingOrders

    /**
     * Inicia a escuta em tempo real dos pedidos no Firebase.
     * Assim que o cliente clicar no site, o app recebe o pedido imediatamente.
     */
    fun startListening() {
        ordersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<OrderRequest>()
                for (child in snapshot.children) {
                    val status = child.child("status").getValue(String::class.java) ?: "pending"
                    if (status == "pending") {
                        val itemsList = mutableListOf<String>()
                        child.child("items").children.forEach { itemSnap ->
                            itemSnap.getValue(String::class.java)?.let { itemsList.add(it) }
                        }

                        val order = OrderRequest(
                            id = child.key ?: "",
                            consoleId = child.child("consoleId").getValue(String::class.java) ?: "PS4",
                            ps4Ip = child.child("ps4Ip").getValue(String::class.java) ?: "127.0.0.1",
                            status = status,
                            createdAt = child.child("createdAt").getValue(Long::class.java) ?: 0L,
                            items = itemsList
                        )
                        list.add(order)
                    }
                }
                _pendingOrders.value = list.reversed() // Mais recentes no topo
            }

            override fun onCancelled(error: DatabaseError) {
                // Erro de conexão ou regras
            }
        })
    }

    /**
     * Aprova o pedido e libera o download na tela do cliente.
     */
    fun approveOrder(orderId: String, onComplete: () -> Unit = {}) {
        val updates = mapOf(
            "status" to "approved",
            "approvedAt" to System.currentTimeMillis()
        )
        ordersRef.child(orderId).updateChildren(updates).addOnCompleteListener {
            onComplete()
        }
    }

    /**
     * Recusa o pedido.
     */
    fun rejectOrder(orderId: String, onComplete: () -> Unit = {}) {
        ordersRef.child(orderId).child("status").setValue("rejected").addOnCompleteListener {
            onComplete()
        }
    }
}
