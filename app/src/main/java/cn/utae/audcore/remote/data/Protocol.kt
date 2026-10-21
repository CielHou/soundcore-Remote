package cn.utae.audcore.remote.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * WebSocket JSON 文本帧中的请求消息（客户端 -> 服务端）。
 * 资源路径和方法沿用 REST 风格；请求用唯一字符串 id 配对响应。
 */
data class RequestMessage(
    val id: String,
    val method: String,
    val path: String,
    val body: JsonElement? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("method", method)
        addProperty("path", path)
        body?.let { add("body", it) }
    }
}

/**
 * 非请求响应的事件消息（服务端 -> 客户端）：
 * - authorized：首次授权后携带 token 与 instance_id
 * - snapshot / update / removed：订阅推送
 */
data class EventMessage(
    val event: String,
    val data: JsonElement? = null,
    val subscriptionId: String? = null,
    val resource: String? = null,
    val sequence: Long? = null,
)

/** 连接状态（供 UI 与前台服务展示）。 */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    AUTHORIZATION_PENDING,
    READY,
    RECONNECTING,
    FAILED,
}
