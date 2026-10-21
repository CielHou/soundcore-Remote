package cn.utae.audcore.remote.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 应用凭证与连接设置存储。
 *
 * 凭证保存在 EncryptedSharedPreferences；首次授权后回填 body.token，
 * 重连时服务端直接返回 200，无须重复等待用户在声核客户端点允许。
 * 凭证绑定实际网页/应用来源，彻底撤销授权要在声核设置中操作。
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "audcore_remote_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 连接地址；多开实例时填各自设置中显示的地址。 */
    var serverUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var appName: String
        get() = prefs.getString(KEY_APP_NAME, DEFAULT_APP_NAME) ?: DEFAULT_APP_NAME
        set(value) = prefs.edit().putString(KEY_APP_NAME, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var instanceId: String?
        get() = prefs.getString(KEY_INSTANCE, null)
        set(value) = prefs.edit().putString(KEY_INSTANCE, value).apply()

    /** 连接成功后自动订阅 /channels。 */
    var autoSubscribe: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SUBSCRIBE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SUBSCRIBE, value).apply()

    /** 编辑模式下隐藏的通道 ID（逗号分隔）。 */
    var hiddenChannelIds: String
        get() = prefs.getString(KEY_HIDDEN_CHANNELS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HIDDEN_CHANNELS, value).apply()

    /** A/B 双轨一键切换：A 轨通道 id。 */
    var abChannelA: String?
        get() = prefs.getString(KEY_AB_A, null)
        set(value) = prefs.edit().putString(KEY_AB_A, value).apply()

    /** A/B 双轨一键切换：B 轨通道 id。 */
    var abChannelB: String?
        get() = prefs.getString(KEY_AB_B, null)
        set(value) = prefs.edit().putString(KEY_AB_B, value).apply()

    /** 抽屉里是否显示「运行日志」入口。 */
    var showLogs: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LOGS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LOGS, value).apply()

    /** 清除本网页/本应用保存的凭证并断开（等于 forgetAuthorization）。 */
    fun forgetAuthorization() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_INSTANCE)
            .apply()
    }

    companion object {
        /** 主实例默认地址。 */
        const val DEFAULT_URL = "ws://127.0.0.1:38533/api/v1"
        const val DEFAULT_APP_NAME = "声核遥控"

        private const val KEY_URL = "server_url"
        private const val KEY_APP_NAME = "app_name"
        private const val KEY_TOKEN = "token"
        private const val KEY_INSTANCE = "instance_id"
        private const val KEY_AUTO_SUBSCRIBE = "auto_subscribe"
        private const val KEY_HIDDEN_CHANNELS = "hidden_channels"
        private const val KEY_AB_A = "ab_channel_a"
        private const val KEY_AB_B = "ab_channel_b"
        private const val KEY_SHOW_LOGS = "show_logs"
    }
}
