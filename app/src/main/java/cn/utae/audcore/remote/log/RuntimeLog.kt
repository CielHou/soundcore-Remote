package cn.utae.audcore.remote.log

import android.content.Context
import android.os.Build
import android.util.Log
import cn.utae.audcore.remote.BuildConfigCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 将关键运行事件与未处理异常写入应用私有目录，便于在没有 adb/logcat 时排查崩溃。
 * 文件仅包含连接状态、协议错误和堆栈；token 等凭证不会写入日志。
 */
object RuntimeLog {
    private const val TAG = "AudcoreRemote"
    private const val FILE_NAME = "runtime.log"
    private const val MAX_BYTES = 1024L * 1024L

    private data class Line(
        val time: String,
        val level: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val queue = LinkedBlockingQueue<Line>()
    private val started = AtomicBoolean(false)
    private val worker = AtomicLong()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (started.compareAndSet(false, true)) {
            Thread({
                writeStartupMarker(appContext)
                runCatching {
                    while (true) {
                        val line = queue.take()
                        write(appContext, line)
                    }
                }
            }, "runtime-log-${worker.incrementAndGet()}").apply {
                isDaemon = true
                start()
            }
        }
        installUncaughtHandler(appContext)
    }

    fun d(message: String) = enqueue("D", message, null)
    fun i(message: String) = enqueue("I", message, null)
    fun w(message: String) = enqueue("W", message, null)
    fun e(message: String, throwable: Throwable? = null) = enqueue("E", message, throwable)

    fun path(context: Context): String =
        File(context.filesDir, FILE_NAME).absolutePath

    fun read(context: Context): String = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) "暂无运行日志" else file.readText()
    }.getOrElse { "读取运行日志失败：${it.message}" }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        i("运行日志已清空")
    }

    private fun installUncaughtHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is LoggingHandler) return
        Thread.setDefaultUncaughtExceptionHandler(LoggingHandler(previous, context))
    }

    private fun writeStartupMarker(context: Context) {
        write(
            context,
            Line(timestamp(), "I", "应用启动；版本=${BuildConfigCompat.NAME}，SDK=${Build.VERSION.SDK_INT}", null),
        )
    }

    private fun enqueue(level: String, message: String, throwable: Throwable?) {
        val safeMessage = sanitize(message)
        Log.d(TAG, safeMessage, throwable)
        runCatching {
            queue.offer(Line(timestamp(), level, safeMessage, throwable))
        }
    }

    private fun write(context: Context, line: Line) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val kept = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(kept)
            }
            file.appendText(buildString {
                append(line.time)
                append(' ')
                append(line.level)
                append(' ')
                append(line.message)
                append('\n')
                if (line.throwable != null) {
                    append(line.throwable.stackTraceToString())
                    append('\n')
                }
            })
        }.onFailure {
            Log.e(TAG, "Write runtime log failed", it)
        }
    }

    private fun timestamp(): String = synchronized(formatter) {
        formatter.format(Date())
    }

    private fun sanitize(message: String): String = message
        .replace(Regex("""(?i)("token"\s*:\s*)"[^"]*""""), "$1\"***\"")
        .replace(Regex("""(?i)(authorization\s*[:=]\s*)\S+"""), "$1***")

    private class LoggingHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
        private val context: Context,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                file.appendText(
                    buildString {
                        append(timestamp())
                        append(" E 未处理异常：线程=")
                        append(thread.name)
                        append('\n')
                        append(throwable.stackTraceToString())
                        append('\n')
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
