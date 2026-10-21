package cn.utae.audcore.remote

import android.app.Application
import cn.utae.audcore.remote.log.RuntimeLog
import cn.utae.audcore.remote.net.AudcoreClient
import cn.utae.audcore.remote.prefs.AppPreferences

class App : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var client: AudcoreClient
        private set

    override fun onCreate() {
        super.onCreate()
        RuntimeLog.initialize(this)
        RuntimeLog.i("正在初始化应用配置")
        preferences = AppPreferences(this)
        client = AudcoreClient(preferences)
        RuntimeLog.i("应用初始化完成，连接地址=${preferences.serverUrl}")
    }
}
