package cn.utae.audcore.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.utae.audcore.remote.data.Channel
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.log.RuntimeLog
import cn.utae.audcore.remote.net.AudcoreClient
import cn.utae.audcore.remote.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val client: AudcoreClient,
    private val prefs: AppPreferences,
) : ViewModel() {

    val state: StateFlow<ConnectionState> = client.state
    private val _channels = MutableStateFlow(client.channels.value)
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    val lastError: StateFlow<String?> = client.lastError

    init {
        viewModelScope.launch {
            client.channels.collect { latest ->
                RuntimeLog.i("ViewModel 收到通道：${latest.size} 条")
                _channels.value = latest
            }
        }
        viewModelScope.launch {
            client.state.collect { latest ->
                RuntimeLog.i("ViewModel 收到连接状态：$latest")
            }
        }
    }

    private val _busyChannelId = MutableStateFlow<String?>(null)
    val busyChannelId: StateFlow<String?> = _busyChannelId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val abChannelA: String? get() = prefs.abChannelA
    val abChannelB: String? get() = prefs.abChannelB

    /** 空白 id 表示清空该轨。 */
    fun setABChannel(slotA: Boolean, channelId: String) {
        val value = channelId.takeIf { it.isNotBlank() }
        if (slotA) prefs.abChannelA = value else prefs.abChannelB = value
    }

    fun clearAB() {
        prefs.abChannelA = null
        prefs.abChannelB = null
    }

    fun connect() = client.connect()

    fun disconnect() = client.disconnect()

    fun refreshChannels() {
        viewModelScope.launch {
            try {
                client.fetchChannelsAndSubscribe()
            } catch (e: Exception) {
                RuntimeLog.e("手动刷新通道列表失败", e)
                _notice.value = e.message ?: "刷新通道失败"
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }


    /**
     * A/B 双轨一键切换：选中的一轨取消静音，另一轨同时静音，
     * 其余通道完全不动（区别于 Solo 的全局独占）。
     * 成功不弹底部提示，失败才提示。
     */
    fun switchAB(a: Channel, b: Channel, targetA: Boolean) {
        val target = if (targetA) a else b
        val other = if (targetA) b else a
        viewModelScope.launch {
            _busyChannelId.value = target.id
            try {
                client.patchChannel(target.id, mute = false)
                client.patchChannel(other.id, mute = true)
                RuntimeLog.i("A/B 切换：${if (targetA) "A" else "B"} -> ${target.id}")
            } catch (e: Exception) {
                RuntimeLog.e("A/B 切换失败", e)
                _notice.value = e.message ?: "A/B 切换失败"
            } finally {
                _busyChannelId.value = null
            }
        }
    }

    /** 手动静音/取消静音单个通道。 */
    fun toggleMute(channel: Channel) {
        viewModelScope.launch {
            _busyChannelId.value = channel.id
            try {
                client.patchChannel(channel.id, mute = channel.mute != true)
            } catch (e: Exception) {
                RuntimeLog.e("切换通道静音状态失败（id=${channel.id}）", e)
                _notice.value = e.message ?: "操作失败"
            } finally {
                _busyChannelId.value = null
            }
        }
    }

    /** 忘记凭证并断开（下次连接需在声核客户端重新允许）。 */
    fun forgetAuthorization() {
        client.forgetAuthorization()
        _notice.value = "已清除应用凭证，下次连接需重新允许"
    }

    class Factory(
        private val client: AudcoreClient,
        private val prefs: AppPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(client, prefs) as T
        }
    }
}
