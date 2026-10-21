package cn.utae.audcore.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import cn.utae.audcore.remote.App
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.log.RuntimeLog
import cn.utae.audcore.remote.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：App 退到后台后保持 WebSocket 连接。
 * Android 8+ 的后台限制会切断普通后台连接，长时间订阅需要前台服务。
 */
class ConnectionService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clientHolder: App

    override fun onCreate() {
        super.onCreate()
        RuntimeLog.i("ConnectionService 创建")
        try {
            clientHolder = application as App
            createChannel()
            startAsForeground()
            RuntimeLog.i("前台服务启动成功，连接地址=${clientHolder.preferences.serverUrl}")
            clientHolder.client.connect()
            observe()
        } catch (t: Throwable) {
            RuntimeLog.e("ConnectionService 创建失败", t)
            stopSelf()
            throw t
        }
    }

    private fun observe() {
        scope.launch {
            clientHolder.client.state.collect { state ->
                if (state == ConnectionState.IDLE) {
                    stopSelf()
                } else {
                    RuntimeLog.i("服务状态更新：$state")
                    notifyState(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        RuntimeLog.i("ConnectionService 销毁")
        scope.cancel()
        // 服务退出只断开连接，不撤销应用授权（与断开连接语义一致）。
        clientHolder.client.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        RuntimeLog.i("调用 startForeground：type=$type，SDK=${Build.VERSION.SDK_INT}")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.state_connecting)),
            type,
        )
    }

    private fun notifyState(state: ConnectionState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(labelOf(state)))
    }

    private fun labelOf(state: ConnectionState): String = when (state) {
        ConnectionState.IDLE -> getString(R.string.state_idle)
        ConnectionState.CONNECTING -> getString(R.string.state_connecting)
        ConnectionState.AUTHORIZATION_PENDING -> getString(R.string.state_auth_pending)
        ConnectionState.READY -> getString(R.string.state_ready)
        ConnectionState.RECONNECTING -> getString(R.string.state_reconnecting)
        ConnectionState.FAILED -> getString(R.string.state_failed)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "audcore_connection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
