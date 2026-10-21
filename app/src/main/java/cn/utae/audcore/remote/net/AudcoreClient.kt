package cn.utae.audcore.remote.net

import cn.utae.audcore.remote.data.Channel
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.data.EventMessage
import cn.utae.audcore.remote.data.RequestMessage
import cn.utae.audcore.remote.log.RuntimeLog
import cn.utae.audcore.remote.prefs.AppPreferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 声核本地 JSON WebSocket 客户端（协议 v1），同时兼容电脑端局域网网关。
 *
 * 声核本机协议要点：
 * - 首条消息必须 POST /connections 申请连接；202 表示等待用户允许（最多 120 秒）。
 * - 用户允许后收到私有 authorized 事件携带 token；凭证放入申请 body.token，验证成功返回 200。
 * - 请求用唯一字符串 id 配对响应；状态码是 JSON 字段，不是 HTTP 响应码。
 * - 断开自动取消全部订阅；这里实现自动重连并重新订阅，撤销授权/拒绝连接后停止。
 * - 每连接最多 32 个订阅、消息上限 64 KiB、每秒最多 120 条请求。
 *
 * 网关模式：
 * - 非 127.0.0.1/localhost 的局域网地址，或默认网关端口 18533，都按电脑网关处理。
 * - 打开后先用 URL 中的 token 执行 AUTH /gateway/authorize，再发送网关兼容的 POST /connections；
 *   认证完成后直接 GET /channels，由网关自动建立声核订阅。
 */
class AudcoreClient(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    companion object {
        /** 协议版本。 */
        const val PROTOCOL_VERSION = "v1"

        /** 等待允许最多 120 秒（这里多给 5 秒容错）。 */
        private const val AUTH_TIMEOUT_MS = 125_000L

        /** 普通请求超时。 */
        private const val REQUEST_TIMEOUT_MS = 10_000L

        private const val CONNECT_TIMEOUT_MS = 3_000L

        /** 订阅 /channels 的默认频率。 */
        const val DEFAULT_SUBSCRIPTION_HZ = 10

        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 15_000L

        /** 电脑网关默认端口。 */
        private const val GATEWAY_PORT = 18533
    }

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        // TCP 连不上时 3 秒即失败，避免 UI 等满 OkHttp 默认 10 秒。
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // 连接后不限制静默读取时间，兼容授权等待、WebSocket 心跳和网关实时推送。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val requestCounter = AtomicInteger(0)

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _authorizationPending = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val authorizationPending: SharedFlow<Unit> = _authorizationPending.asSharedFlow()

    private val _events = MutableSharedFlow<EventMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<EventMessage> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    @Volatile
    private var connected = false

    @Volatile
    private var authorizationDenied = false

    @Volatile
    private var reconnectAttempts = 0

    private var channelSubscriptionId: String? = null

    private var initialized = false

    /** 判断当前地址是否为电脑网关；网关不使用声核 POST /connections 授权流程。 */
    private fun isGatewayUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val port = uri.port
        if (port == GATEWAY_PORT) return true
        return host != "127.0.0.1" && host != "localhost" && host != "::1"
    }

    /** 发起连接（幂等）。 */
    fun connect() {
        if (connected || _state.value == ConnectionState.CONNECTING) {
            RuntimeLog.i("请求连接被忽略：connected=$connected，state=${_state.value}")
            return
        }
        authorizationDenied = false
        initialized = false
        RuntimeLog.i("开始连接 ${prefs.serverUrl}")
        openSocket()
    }

    /** 主动断开；服务端关闭连接后会取消全部订阅。保留应用授权。 */
    fun disconnect() {
        RuntimeLog.i("断开 WebSocket 连接")
        reconnectAttempts = 0
        connected = false
        initialized = false
        _state.value = ConnectionState.IDLE
        failAllPending("连接已断开")
        webSocket?.close(1000, "用户退出")
        webSocket = null
        channelSubscriptionId = null
    }

    /** 清除凭证并断开（下次连接需要重新允许）。 */
    fun forgetAuthorization() {
        prefs.forgetAuthorization()
        disconnect()
    }

    /**
     * 发送一个协议请求并等待配对响应。
     * @return 完整响应 JSON（含 status 与 data/error）。
     */
    suspend fun request(method: String, path: String, body: JsonElement? = null): JsonObject {
        val socket = webSocket
            ?: throw IllegalStateException("WebSocket 未连接")
        val id = "${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val message = RequestMessage(id = id, method = method, path = path, body = body)
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            if (!socket.send(gson.toJson(message.toJson()))) {
                throw IllegalStateException("发送失败：连接正在关闭")
            }
            return withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** 读取通道列表并（按设置）订阅实时变化；网关自动建立订阅，无需 POST /subscriptions。 */
    suspend fun fetchChannelsAndSubscribe() {
        val response = request("GET", "/channels")
        applyChannelsPayload(response.get("data"))
        if (!isGatewayUrl(prefs.serverUrl) && prefs.autoSubscribe) {
            subscribeChannels()
        }
    }

    /** POST /subscriptions 订阅 /channels。 */
    suspend fun subscribeChannels() {
        val body = JsonObject().apply {
            addProperty("resource", "/channels")
            addProperty("frequency_hz", DEFAULT_SUBSCRIPTION_HZ)
        }
        val response = request("POST", "/subscriptions", body)
        if (response.get("status")?.takeIf { it.isJsonPrimitive }?.asInt == 201) {
            channelSubscriptionId =
                response.getAsJsonObject("data").get("subscription_id").asString
        }
    }

    /** PATCH /channels/{id}：写入 mute 或 gain_db（-90 至 12 dB）。网关同样接受该请求。 */
    suspend fun patchChannel(channelId: String, mute: Boolean? = null, gainDb: Float? = null) {
        val body = JsonObject().apply {
            mute?.let { addProperty("mute", it) }
            gainDb?.let { addProperty("gain_db", it) }
        }
        val response = request("PATCH", "/channels/$channelId", body)
        // 网关的成功响应会带 event；状态码仍是可选 JSON 字段。
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
        if (status == 200) {
            mergeChannel(response.get("data"))
        } else {
            throw ApiException(status, errorOf(response))
        }
    }


    /** 只读查询 GET /info。 */
    suspend fun getInfo(): JsonObject = unwrapData(request("GET", "/info"))

    /** 只读查询 GET /status。 */
    suspend fun getStatus(): JsonObject = unwrapData(request("GET", "/status"))

    // ---------------------------------------------------------------- 连接

    private fun openSocket() {
        _state.value =
            if (reconnectAttempts == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
        _lastError.value = null
        RuntimeLog.i("创建 WebSocket：${prefs.serverUrl}，state=${_state.value}")
        val request = Request.Builder().url(prefs.serverUrl).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                reconnectAttempts = 0
                RuntimeLog.i("WebSocket 已打开")
                if (isGatewayUrl(prefs.serverUrl)) {
                    sendGatewayAuthorization(ws)
                } else {
                    sendConnectionRequest(ws)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected = false
                initialized = false
                failAllPending(t.message ?: "连接失败")
                val code = response?.code
                val error = if (code != null) {
                    "连接失败 HTTP $code：${t.message}"
                } else {
                    "连接失败：${t.message}"
                }
                RuntimeLog.e(error, t)
                _lastError.value = error
                // 403/401 通常不是临时断线，重复重连只会触发同一拒绝。
                if (code == 401 || code == 403) {
                    authorizationDenied = true
                    _state.value = ConnectionState.FAILED
                }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                initialized = false
                failAllPending("连接已关闭：$reason")
                if (_state.value != ConnectionState.IDLE) {
                    // 1000 = 用户主动退出，不再重连。
                    if (code == 1000) {
                        _state.value = ConnectionState.IDLE
                    } else {
                        val error = "连接已关闭：$reason（code=$code）"
                        RuntimeLog.w(error)
                        _lastError.value = error
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    /**
     * 网关连接必须先认证，再读取资源。
     *
     * 旧版实现先发送声核 POST /connections，导致网关在收到 AUTH 前拒绝 GET /channels。
     * 现在由网关使用 URL 中的局域网 token 做第一层校验，并由这条请求完成第二层认证；
     * 认证成功后再发送网关模式的 POST /connections 兼容握手，最后统一初始化。
     */
    private fun sendGatewayAuthorization(ws: WebSocket) {
        val authId = "${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val authMessage = RequestMessage(
            id = authId,
            method = "AUTH",
            path = "/gateway/authorize",
            body = JsonObject().apply {
                val token = gatewayToken(prefs.serverUrl)
                    ?: throw IllegalStateException("网关地址缺少 token 查询参数")
                addProperty("token", token)
            },
        )
        val authDeferred = CompletableDeferred<JsonObject>()
        pending[authId] = authDeferred
        ws.send(gson.toJson(authMessage.toJson()))
        RuntimeLog.i("已发送网关 token 认证请求")

        scope.launch {
            try {
                val response = withTimeout(REQUEST_TIMEOUT_MS) { authDeferred.await() }
                val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
                if (status >= 400) {
                    val error = errorOf(response)
                    val message = "网关认证失败($status)：${error.message}"
                    RuntimeLog.e(message)
                    _lastError.value = message
                    authorizationDenied = true
                    _state.value = ConnectionState.FAILED
                    return@launch
                }

                RuntimeLog.i("网关 token 认证成功（status=$status）")
                val connectionsId = "${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
                val connectionsDeferred = CompletableDeferred<JsonObject>()
                pending[connectionsId] = connectionsDeferred
                ws.send(
                    gson.toJson(
                        RequestMessage(
                            id = connectionsId,
                            method = "POST",
                            path = "/connections",
                            body = JsonObject().apply { addProperty("app_name", prefs.appName) },
                        ).toJson()
                    )
                )
                RuntimeLog.i("已发送网关兼容 POST /connections")
                val connectionsResponse = withTimeout(REQUEST_TIMEOUT_MS) { connectionsDeferred.await() }
                val connectionsStatus =
                    connectionsResponse.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
                if (connectionsStatus >= 400) {
                    val error = errorOf(connectionsResponse)
                    val message = "网关连接失败($connectionsStatus)：${error.message}"
                    RuntimeLog.e(message)
                    _lastError.value = message
                    _state.value = ConnectionState.FAILED
                    return@launch
                }
                onReady()
            } catch (e: Exception) {
                RuntimeLog.e("网关认证流程失败", e)
                _lastError.value = "网关认证失败：${e.message}"
                _state.value = ConnectionState.FAILED
            }
        }
    }

    private fun gatewayToken(url: String): String? =
        runCatching { java.net.URI(url) }.getOrNull()
            ?.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("token=") }
            ?.substringAfter("token=")
            ?.takeIf { it.isNotBlank() }

    private fun sendConnectionRequest(ws: WebSocket) {
        val gateway = isGatewayUrl(prefs.serverUrl)
        val body = JsonObject().apply {
            addProperty("app_name", prefs.appName)
            if (!gateway) {
                prefs.token?.takeIf { it.isNotBlank() }?.let { addProperty("token", it) }
            }
        }
        val id = "${requestCounter.incrementAndGet()}-${UUID.randomUUID()}"
        val message = RequestMessage(id = id, method = "POST", path = "/connections", body = body)
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        ws.send(gson.toJson(message.toJson()))
        RuntimeLog.i(
            "已发送 POST /connections，模式=${if (gateway) "网关" else "声核"}，" +
                "token=${if (prefs.token == null) "无" else "有"}"
        )

        scope.launch {
            val response = withTimeout(AUTH_TIMEOUT_MS) { deferred.await() }
            val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
            when {
                status >= 400 -> {
                    val error = errorOf(response)
                    authorizationDenied = error.code == "authorization_denied"
                    val message = "申请连接失败($status)：${error.message}"
                    RuntimeLog.e(message)
                    _lastError.value = message
                    _state.value = ConnectionState.FAILED
                }

                status == 200 -> {
                    response.get("data")?.asJsonObject?.get("instance_id")?.asString?.let {
                        prefs.instanceId = it
                    }
                    RuntimeLog.i("连接凭证验证成功（status=200）")
                    onReady()
                }

                status == 202 -> {
                    RuntimeLog.i("等待用户在声核客户端允许连接（status=202）")
                    _state.value = ConnectionState.AUTHORIZATION_PENDING
                    _authorizationPending.tryEmit(Unit)
                }

                else -> {
                    val error = errorOf(response)
                    authorizationDenied = error.code == "authorization_denied"
                    val message = "申请连接失败($status)：${error.message}"
                    RuntimeLog.e(message)
                    _lastError.value = message
                    _state.value = ConnectionState.FAILED
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return

        // 1. 配对响应
        val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        if (id != null) {
            pending.remove(id)?.complete(json)
            // AUTH /gateway/authorize 也可能带 id，同时保留事件继续处理。
            if (json.get("event") == null) return
        }

        // 2. 事件
        val event = json.get("event")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val message = EventMessage(
            event = event,
            data = json.get("data"),
            subscriptionId = json.get("subscription_id")?.takeIf { it.isJsonPrimitive }?.asString,
            resource = json.get("resource")?.takeIf { it.isJsonPrimitive }?.asString,
            sequence = json.get("sequence")?.takeIf { it.isJsonPrimitive }?.asLong,
        )

        when (event) {
            "authorized" -> {
                val data = message.data?.asJsonObject
                data?.get("token")?.asString?.let { prefs.token = it }
                data?.get("instance_id")?.asString?.let { prefs.instanceId = it }
                RuntimeLog.i("收到 authorized 事件，保存凭证")
                scope.launch { onReady() }
            }

            "gateway-ready" -> RuntimeLog.i("已连接电脑网关")

            "auth-required" -> RuntimeLog.i("网关等待局域网 token 认证")

            "gateway-authorized" -> {
                RuntimeLog.i("已通过网关 token 认证")
                scope.launch { onReady() }
            }

            "gateway-error" -> {
                val error = json.get("error")?.asJsonObject
                val status = json.get("status")?.takeIf { it.isJsonPrimitive }?.asInt
                val messageText = error?.get("message")?.asString ?: "网关请求失败"
                RuntimeLog.e("网关错误($status)：$messageText")
                if (status != null && status >= 400) {
                    _lastError.value = "网关错误($status)：$messageText"
                }
            }

            "snapshot" -> {
                if (message.resource == null || message.resource == "/channels") {
                    applyChannelsPayload(message.data)
                    RuntimeLog.i("收到通道快照：${_channels.value.size} 个通道")
                }
            }

            "channels" -> {
                applyChannelsPayload(message.data)
                RuntimeLog.i("收到网关通道列表：${_channels.value.size} 个通道")
            }

            "channels-update" -> {
                if (message.data != null && message.data.isJsonArray) {
                    applyChannelsPayload(message.data)
                } else {
                    mergeChannel(message.data)
                }
            }

            "channel-updated" -> mergeChannel(message.data)
            "update" -> mergeChannel(message.data)

            "removed" -> {
                val resourceId = message.resource?.substringAfterLast('/')
                if (resourceId != null) {
                    _channels.value = _channels.value.filterNot { it.id == resourceId }
                    RuntimeLog.i("通道已移除：$resourceId")
                }
            }
        }

        _events.tryEmit(message)
    }

    private suspend fun onReady() {
        if (initialized) return
        initialized = true
        _state.value = ConnectionState.READY
        _lastError.value = null
        RuntimeLog.i("连接已授权，开始拉取通道列表")
        try {
            fetchChannelsAndSubscribe()
            RuntimeLog.i("通道初始化完成：${_channels.value.size} 个通道")
        } catch (e: Exception) {
            initialized = false
            RuntimeLog.e("初始化通道失败", e)
            _lastError.value = "初始化通道失败：${e.message}"
        }
    }

    private fun scheduleReconnect() {
        if (authorizationDenied || _state.value == ConnectionState.IDLE ||
            _state.value == ConnectionState.FAILED
        ) {
            RuntimeLog.w("停止重连：denied=$authorizationDenied，state=${_state.value}")
            if (_state.value != ConnectionState.FAILED) _state.value = ConnectionState.FAILED
            return
        }
        _state.value = ConnectionState.RECONNECTING
        val delayMs = (RECONNECT_BASE_DELAY_MS shl reconnectAttempts.coerceAtMost(4))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempts++
        RuntimeLog.w("将在 ${delayMs}ms 后重连，第 $reconnectAttempts 次")
        scope.launch {
            delay(delayMs)
            if (!connected && _state.value == ConnectionState.RECONNECTING) openSocket()
        }
    }

    private fun failAllPending(reason: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        pending.clear()
    }

    // ------------------------------------------------------------ 数据合并

    private fun applyChannelsPayload(data: JsonElement?) {
        if (data == null || !data.isJsonArray) return
        val list = data.asJsonArray.mapNotNull { element ->
            runCatching { gson.fromJson(element, Channel::class.java) }.getOrNull()
        }
        // visual_order：普通通道在前、右侧常驻通道在后。
        _channels.value = list.sortedBy { it.displayOrder }
        RuntimeLog.i("通道数据已更新：写入 ${_channels.value.size} 条")
    }

    private fun mergeChannel(data: JsonElement?) {
        if (data == null || !data.isJsonObject) return
        val updated = runCatching { gson.fromJson(data, Channel::class.java) }.getOrNull() ?: return
        _channels.value = _channels.value
            .map { if (it.id == updated.id) it.mergeWith(updated) else it }
            .let { current ->
                if (current.any { it.id == updated.id }) current
                else (current + updated).sortedBy { it.displayOrder }
            }
    }

    private fun Channel.mergeWith(update: Channel): Channel = copy(
        // update 可能是部分字段（订阅增量），null 视为“未提供”。
        id = update.id,
        index = update.index,
        name = update.name ?: name,
        gain_db = update.gain_db ?: gain_db,
        mute = update.mute ?: mute,
        color = update.color ?: color,
        visual_index = update.visual_index ?: visual_index,
        align_right = update.align_right ?: align_right,
        inputs = update.inputs ?: inputs,
        sends = update.sends ?: sends,
    )

    private fun unwrapData(response: JsonObject): JsonObject =
        response.get("data")?.asJsonObject
            ?: throw ApiException(response.get("status")?.asInt ?: 0, errorOf(response))

    private fun errorOf(response: JsonObject): ApiErrorHolder {
        val error = response.get("data")?.asJsonObject?.get("error")?.asJsonObject
        return ApiErrorHolder(
            code = error?.get("code")?.asString,
            message = error?.get("message")?.asString ?: response.toString(),
        )
    }
}

/** 协议错误：status 为 JSON 字段状态码。 */
class ApiException(val status: Int, val error: ApiErrorHolder) :
    Exception("[$status] ${error.message}")

data class ApiErrorHolder(val code: String?, val message: String?)
