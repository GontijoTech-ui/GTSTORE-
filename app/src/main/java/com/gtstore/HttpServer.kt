package com.gtstore

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

// Estados possíveis do pedido
enum class OrderStatus {
    PENDING,
    APPROVED,
    REJECTED
}

// Representação de um pedido de checkout
data class Order(
    val id: String,
    val items: List<String>,      // Lista com os nomes ou links dos PKGs
    var status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

object OrderManager {
    // Mapa em memória concorrente (Thread-safe)
    private val orders = ConcurrentHashMap<String, Order>()

    fun createOrder(items: List<String>): Order {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val order = Order(id = id, items = items)
        orders[id] = order
        return order
    }

    fun getOrder(id: String): Order? = orders[id]

    fun updateStatus(id: String, status: OrderStatus): Boolean {
        val order = orders[id] ?: return false
        order.status = status
        return true
    }

    fun getPendingOrders(): List<Order> {
        return orders.values.filter { it.status == OrderStatus.PENDING }
    }
}

/**
 * Roteador de requisições da API.
 * Integre estas chamadas dentro do método principal que processa requisições HTTP
 * (ex: serve(), handle(), processRequest(), dependendo da biblioteca HTTP usada).
 */
class ApiRouter {

    companion object {
        // Headers padrão com suporte total a CORS e sem cache
        val CORS_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Accept",
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Content-Type" to "application/json; charset=utf-8"
        )
    }

    /**
     * 1. Rota: POST /api/order/create
     * Chamada pelo carrinho do PS4 ao clicar em Checkout.
     */
    fun handleCreateOrder(requestBody: String): String {
        return try {
            val json = JSONObject(requestBody)
            val itemsJson = json.optJSONArray("items") ?: JSONArray()
            val itemsList = mutableListOf<String>()
            for (i in 0 until itemsJson.length()) {
                itemsList.add(itemsJson.getString(i))
            }

            val order = OrderManager.createOrder(itemsList)
            val response = JSONObject().apply {
                put("success", true)
                put("orderId", order.id)
                put("status", order.status.name.lowercase())
            }
            response.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Erro ao criar pedido")
            }.toString()
        }
    }

    /**
     * 2. Rota: GET /api/order/status?id=XYZ
     * Chamada periodicamente pelo PS4 (polling) aguardando liberação.
     */
    fun handleGetOrderStatus(orderId: String?): String {
        if (orderId.isNullOrBlank()) {
            return JSONObject().apply {
                put("error", "Parâmetro 'id' ausente")
            }.toString()
        }

        val order = OrderManager.getOrder(orderId)
        if (order == null) {
            return JSONObject().apply {
                put("status", "not_found")
            }.toString()
        }

        val itemsArray = JSONArray()
        order.items.forEach { itemsArray.put(it) }

        return JSONObject().apply {
            put("id", order.id)
            put("status", order.status.name.lowercase()) // "pending", "approved" ou "rejected"
            put("items", itemsArray)
        }.toString()
    }

    /**
     * 3. Rota: GET /api/admin/orders
     * Chamada pela aba administrativa para exibir solicitações pendentes.
     */
    fun handleGetAdminOrders(): String {
        val pending = OrderManager.getPendingOrders()
        val array = JSONArray()

        pending.forEach { order ->
            val obj = JSONObject().apply {
                put("id", order.id)
                put("status", order.status.name.lowercase())
                val itemsArr = JSONArray()
                order.items.forEach { itemsArr.put(it) }
                put("items", itemsArr)
                put("createdAt", order.createdAt)
            }
            array.put(obj)
        }

        return JSONObject().apply {
            put("orders", array)
        }.toString()
    }

    /**
     * 4. Rota: POST /api/admin/order/approve
     * Chamada quando o botão "Autorizar" é clicado na aba admin.
     * Body esperado: {"id": "XYZ", "action": "approve"} ou {"action": "reject"}
     */
    fun handleApproveOrder(requestBody: String): String {
        return try {
            val json = JSONObject(requestBody)
            val orderId = json.getString("id")
            val action = json.optString("action", "approve")

            val newStatus = if (action == "approve") OrderStatus.APPROVED else OrderStatus.REJECTED
            val updated = OrderManager.updateStatus(orderId, newStatus)

            JSONObject().apply {
                put("success", updated)
                put("id", orderId)
                put("status", newStatus.name.lowercase())
            }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Erro ao atualizar status")
            }.toString()
        }
    }
}
