package dev.zk.usbip

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App log: everything goes to logcat as before, and also to daily files in
 * filesDir/logs (usbip-YYYY-MM-DD.log, kept for [KEEP_DAYS] days) so it survives restarts and can be
 * shared from the Activity screen. Per-URB tracing stays logcat-only (too chatty for a file).
 */
object AppLog {
    private const val KEEP_DAYS = 7
    private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    private const val RECENT_LINES = 300

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "UsbIp-log").apply { isDaemon = true } }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var dir: File? = null

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** Newest first, for the Activity screen (no stack traces). */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun init(context: Context) {
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        writer.execute {
            prune(d)
            val seeded = files(d).asReversed().asSequence()
                .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).asReversed().asSequence() }
                .filter { it.length > 24 && it[4] == '-' } // skip stack-trace and continuation lines
                .take(RECENT_LINES)
                .map { it.substring(5, 19) + it.substring(23) } // "MM-dd HH:mm:ss" + " I tag: msg"
                .toList()
            // Lines logged since init() are newer than anything on disk.
            _recent.update { (it + seeded).take(RECENT_LINES) }
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        i("UsbIp", "app started: version $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun i(tag: String, message: String) = write('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        when (level) {
            'E' -> Log.e(tag, message, error)
            'W' -> Log.w(tag, message, error)
            else -> Log.i(tag, message)
        }
        val now = Date()
        val stamp = synchronized(timeFormat) { timeFormat.format(now) }
        val summary = if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message
        _recent.update { (listOf("${stamp.substring(5, 19)} $level $tag: $summary") + it).take(RECENT_LINES) }
        val d = dir ?: return
        writer.execute {
            runCatching {
                val day = synchronized(dayFormat) { dayFormat.format(now) }
                val text = buildString {
                    append(stamp).append(' ').append(level).append(' ').append(tag).append(": ").append(message).append('\n')
                    if (error != null) Log.getStackTraceString(error).lineSequence()
                        .filter { it.isNotBlank() }.forEach { append("    ").append(it).append('\n') }
                }
                File(d, "usbip-$day.log").appendText(text)
            }
        }
    }

    /** All kept log files, oldest first, joined into one file in the cache for sharing. */
    fun exportForSharing(context: Context): File {
        val d = dir ?: File(context.filesDir, "logs")
        // Wait for queued writes so the export includes the latest lines.
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
        val outDir = File(context.cacheDir, "shared-logs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(outDir, "usbip-android-log-$stamp.txt")
        out.bufferedWriter().use { w ->
            w.write("USB/IP Server log, exported ${Date()}\n")
            w.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
            for (f in files(d)) {
                w.write("===== ${f.name} =====\n")
                f.bufferedReader().use { it.copyTo(w) }
                w.write("\n")
            }
        }
        return out
    }

    fun clear() {
        val d = dir ?: return
        writer.execute { files(d).forEach { it.delete() } }
        _recent.value = emptyList()
    }

    private fun files(d: File): List<File> =
        d.listFiles { f -> f.name.startsWith("usbip-") && f.name.endsWith(".log") }.orEmpty().sortedBy { it.name }

    private fun prune(d: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS.toLong())
        var all = files(d)
        all.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        all = files(d)
        var total = all.sumOf { it.length() }
        for (f in all) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
