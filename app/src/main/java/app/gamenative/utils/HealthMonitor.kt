package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.ui.util.SnackbarManager
import com.winlator.core.ProcessHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * Watches the app from a background thread:
 *
 * - If the UI thread stops responding for a few seconds, writes a report (what every thread was
 *   doing, free memory and storage) to `Android/data/<package>/files/freeze_logs/`, so a freeze can
 *   be traced afterwards.
 * - While a game is running, warns when the internal storage is almost full.
 */
object HealthMonitor {
    private const val TICK_MS = 1_000L
    private const val FREEZE_MS = 4_000L
    private const val STORAGE_CHECK_MS = 60_000L
    private const val STORAGE_WARN_EVERY_MS = 10 * 60_000L
    private const val LOW_STORAGE_BYTES = 200L * 1024 * 1024
    private const val REPORTS_KEPT = 5
    private const val MEMORY_LOG_MS = 5 * 60_000L
    private const val MEMORY_WARN_EVERY_MS = 10 * 60_000L
    private const val MEMORY_LOG_MAX_BYTES = 256L * 1024

    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastBeat = 0L

    private val beat = object : Runnable {
        override fun run() {
            lastBeat = SystemClock.uptimeMillis()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        lastBeat = SystemClock.uptimeMillis()
        mainHandler.post(beat)
        thread(isDaemon = true, name = "HealthMonitor") { watch(app) }
    }

    private fun watch(app: Context) {
        var reported = false
        var lastTick = SystemClock.uptimeMillis()
        var lastStorageCheck = 0L
        var lastStorageWarning = 0L
        var lastMemoryLog = 0L
        var lastMemoryWarning = 0L
        while (true) {
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                return
            }
            runCatching {
                val now = SystemClock.uptimeMillis()
                // If this thread itself was held up (the system froze the whole app in the
                // background), the UI thread was too: that is not a freeze worth reporting.
                val selfDelayed = now - lastTick > TICK_MS + 1_500L
                lastTick = now
                if (selfDelayed) {
                    lastBeat = now
                    return@runCatching
                }

                val stalledMs = now - lastBeat
                if (stalledMs > FREEZE_MS) {
                    if (!reported) {
                        reported = true
                        writeFreezeReport(app, stalledMs)
                    }
                } else {
                    reported = false
                }

                if (PluviaApp.keepAlive && now - lastStorageCheck > STORAGE_CHECK_MS) {
                    lastStorageCheck = now
                    val free = freeBytes(app.filesDir)
                    if (free in 0 until LOW_STORAGE_BYTES &&
                        (lastStorageWarning == 0L || now - lastStorageWarning > STORAGE_WARN_EVERY_MS)
                    ) {
                        lastStorageWarning = now
                        SnackbarManager.showTop("Storage almost full: ${free / (1024 * 1024)} MB free. Free up space soon.")
                    }
                }

                if (PluviaApp.keepAlive && now - lastMemoryLog > MEMORY_LOG_MS) {
                    lastMemoryLog = now
                    val memory = memoryInfo(app)
                    logMemory(app, memory)
                    if (memory.lowMemory &&
                        (lastMemoryWarning == 0L || now - lastMemoryWarning > MEMORY_WARN_EVERY_MS)
                    ) {
                        lastMemoryWarning = now
                        SnackbarManager.showTop("The device is low on memory. Save and restart the game soon.")
                    }
                }
            }.onFailure { Timber.w(it, "HealthMonitor tick failed") }
        }
    }

    /** Free bytes on the volume holding [dir], or -1 when it cannot be read. */
    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(-1L)

    private fun memoryInfo(app: Context): ActivityManager.MemoryInfo {
        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        return memory
    }

    /**
     * Appends one line every few minutes of play to `freeze_logs/memory.log`: free device memory and
     * the memory used by this app's own processes (the game included). A figure that keeps climbing
     * over a session points to a leak; a flat one rules memory out.
     */
    private fun logMemory(app: Context, memory: ActivityManager.MemoryInfo) {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "freeze_logs")
        if (!dir.isDirectory && !dir.mkdirs()) return
        val log = File(dir, "memory.log")
        if (log.length() > MEMORY_LOG_MAX_BYTES) {
            val old = File(dir, "memory.old.log")
            old.delete()
            log.renameTo(old)
        }
        val gameMb = ProcessHelper.listSubProcesses().sumOf { it.rssBytes } / (1024 * 1024)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        log.appendText(
            "$stamp device free ${memory.availMem / (1024 * 1024)} MB of ${memory.totalMem / (1024 * 1024)} MB" +
                ", low=${memory.lowMemory}, game processes $gameMb MB" +
                ", storage free ${freeBytes(app.filesDir) / (1024 * 1024)} MB\n",
        )
    }

    private fun writeFreezeReport(app: Context, stalledMs: Long) {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "freeze_logs")
        if (!dir.isDirectory && !dir.mkdirs()) return
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        val memory = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val mainThread = Looper.getMainLooper().thread

        val report = buildString {
            appendLine("UI thread not responding for $stalledMs ms")
            appendLine("Time: $stamp")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Game running: ${PluviaApp.keepAlive}, suspended: ${ProcessHelper.isPauseActive()}, app in foreground: ${PluviaApp.isActivityInForeground}")
            appendLine("Free internal storage: ${freeBytes(app.filesDir) / (1024 * 1024)} MB")
            appendLine("Device memory: ${memory.availMem / (1024 * 1024)} MB free of ${memory.totalMem / (1024 * 1024)} MB, low: ${memory.lowMemory}")
            appendLine("App heap: ${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)} MB used of ${runtime.maxMemory() / (1024 * 1024)} MB")
            appendLine()
            appendLine("---------- UI thread ----------")
            mainThread.stackTrace.forEach { appendLine("    at $it") }
            appendLine()
            appendLine("---------- Other threads ----------")
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                if (thread !== mainThread && stack.isNotEmpty()) {
                    appendLine("\"${thread.name}\" (${thread.state})")
                    stack.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }
        }
        File(dir, "freeze_$stamp.txt").writeText(report)
        Timber.w("UI thread not responding for %d ms; report written to %s", stalledMs, dir.path)

        dir.listFiles { file -> file.name.startsWith("freeze_") }
            ?.sortedByDescending { it.lastModified() }?.drop(REPORTS_KEPT)?.forEach { it.delete() }
    }
}
