package pl.dubba.share.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Disk-backed debug log. Lines are appended to a current-file under app
 * private storage; when it crosses [MAX_CURRENT_BYTES] the current is rotated
 * to `.old` (overwriting any previous rotation), and a fresh current starts.
 * Combined cap is roughly [MAX_CURRENT_BYTES] × 2 - small enough that nothing
 * ever blocks on it, big enough to capture a whole AFK session.
 *
 * The persistence here covers the gap that the in-memory [pl.dubba.share.net.ConnectionState._logs]
 * doesn't: a process kill (system reclamation, force-stop, OOM) wipes the
 * StateFlow, but the disk file survives, so the Debug screen can show what
 * happened in the previous session and the user can export it.
 *
 * Lines come pre-timestamped; this object adds nothing - keeps formatting
 * decisions in one place ([timestamp] helper exposed below for callers).
 */
object DebugLog {

    /**
     * Current-file size cap. When current exceeds this, it's renamed to `.old`
     * and a fresh empty current is started. 32 KiB gives ~1k lines depending
     * on content; doubling for the .old means ~64 KiB total which is the
     * "plenty" target the user asked for.
     */
    private const val MAX_CURRENT_BYTES: Long = 32L * 1024L

    private const val CURRENT_NAME = "debug.log"
    private const val OLD_NAME     = "debug.log.old"
    private const val EXPORT_NAME  = "debug-export.log"

    @Volatile
    private var dir: File? = null

    private val lock = Any()

    private val tsFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /** Prefix for any log line; callers append the message. */
    fun timestamp(): String = "[${tsFormat.get()!!.format(Date())}]"

    /**
     * Idempotent - first call wires the storage dir, subsequent calls are
     * no-ops. Safe to call from both [pl.dubba.share.MainActivity.onCreate]
     * and [pl.dubba.share.net.ConnectionService.onCreate]; whichever runs
     * first wins.
     */
    fun init(context: Context) {
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            dir = context.applicationContext.filesDir
        }
    }

    /** Best-effort write. Never throws - failure to log is not failure to run. */
    fun append(line: String) {
        val d = dir ?: return
        synchronized(lock) {
            try {
                val current = File(d, CURRENT_NAME)
                current.appendText("$line\n")
                if (current.length() > MAX_CURRENT_BYTES) {
                    val old = File(d, OLD_NAME)
                    if (old.exists()) old.delete()
                    current.renameTo(old)
                }
            } catch (_: Throwable) { /* swallow */ }
        }
    }

    /**
     * Returns previous + current log lines concatenated, oldest first. Used to
     * seed the in-memory state on app start so the Debug screen survives a
     * process kill.
     */
    fun loadAllLines(): List<String> {
        val d = dir ?: return emptyList()
        synchronized(lock) {
            val out = mutableListOf<String>()
            File(d, OLD_NAME).takeIf { it.exists() }?.let { out += it.readLines() }
            File(d, CURRENT_NAME).takeIf { it.exists() }?.let { out += it.readLines() }
            return out
        }
    }

    /** Wipes both rotation slots. Called from the Debug screen's Clear button. */
    fun clearAll() {
        val d = dir ?: return
        synchronized(lock) {
            File(d, OLD_NAME).takeIf { it.exists() }?.delete()
            File(d, CURRENT_NAME).takeIf { it.exists() }?.delete()
        }
    }

    /**
     * Builds an ACTION_SEND intent with the combined log file attached as a
     * FileProvider URI - pops the system share sheet so the user can choose
     * Gmail / Drive / Save-to-file / Signal / whatever. Returns null if the
     * dir hasn't been initialised or the file can't be built.
     */
    fun makeShareIntent(context: Context): Intent? {
        val uri = exportAndUri(context) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Share location debug log")
            clipData = ClipData.newRawUri("log", uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun exportAndUri(context: Context): Uri? {
        val d = dir ?: return null
        synchronized(lock) {
            return try {
                val export = File(d, EXPORT_NAME)
                export.writeText("")
                File(d, OLD_NAME).takeIf { it.exists() }?.let { export.appendText(it.readText()) }
                File(d, CURRENT_NAME).takeIf { it.exists() }?.let { export.appendText(it.readText()) }
                val authority = "${context.applicationContext.packageName}.fileprovider"
                FileProvider.getUriForFile(context.applicationContext, authority, export)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
