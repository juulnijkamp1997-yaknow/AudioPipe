package com.asdfg.soundmaster.hub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.asdfg.soundmaster.R
import com.asdfg.soundmaster.audio.RootRouting
import com.asdfg.soundmaster.root.RoutingHelper
import java.io.File

/**
 * The app's side of the Bluetooth hub. The hub itself runs as root in its own process (see
 * HubDaemon); the app only talks to it through three files in its own files directory:
 * hub.conf (which apps), hub.stop (please stop) and hub.status (what the hub is doing).
 * Only starting the hub needs root.
 *
 * Functions that touch root or wait block: call them from a background thread.
 */
object HubControl {

    private const val PREFS = "hub"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_WAS_ON = "was_on"

    private const val CONFIG = "hub.conf"
    private const val STATUS = "hub.status"
    private const val STOP = "hub.stop"
    const val LOG_FILE = "/data/local/tmp/audiopipe-hub.log"

    private const val CHANNEL = "hub"
    private const val NOTIFICATION_ID = 7

    /** The hub writes its status at least every five seconds; older than this means it is gone. */
    private const val STALE_MS = 15_000L

    const val HELPER_HUB_RELEASE = "hub-release"

    data class Status(
        val state: String,
        val code: String,
        val detail: String,
        val device: String,
        val uids: List<Int>,
        val version: String,
        val pid: Int,
        val time: Long,
    ) {
        val isRunning: Boolean
            get() = state != "stopped" && System.currentTimeMillis() - time < STALE_MS
    }

    enum class Offload { NOT_USED, OFF, ON, UNKNOWN }

    // ---- choices ----

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    /** Set while the hub runs, so a reboot can clean up after it. */
    fun wasOn(context: Context): Boolean = prefs(context).getBoolean(KEY_WAS_ON, false)

    fun setWasOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAS_ON, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- files shared with the hub ----

    fun statusFileName(): String = STATUS

    fun readStatus(context: Context): Status? {
        val file = File(context.filesDir, STATUS)
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val values = text.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()
        return Status(
            state = values["state"].orEmpty(),
            code = values["code"].orEmpty(),
            detail = values["detail"].orEmpty(),
            device = values["device"].orEmpty(),
            uids = values["uids"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() },
            version = values["version"].orEmpty(),
            pid = values["pid"]?.toIntOrNull() ?: -1,
            time = values["time"]?.toLongOrNull() ?: 0L,
        )
    }

    fun isRunning(context: Context): Boolean = readStatus(context)?.isRunning == true

    /** Tells the hub which apps go to the speaker. A running hub picks it up within a second. */
    fun writeConfig(context: Context) {
        val uids = uidsFor(context, selectedPackages(context))
        val file = File(context.filesDir, CONFIG)
        val temp = File(context.filesDir, "$CONFIG.tmp")
        temp.writeText("uids=${uids.joinToString(",")}\n")
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    fun uidsFor(context: Context, packages: Set<String>): List<Int> =
        packages.mapNotNull { pkg ->
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
        }.distinct().sorted()

    // ---- start and stop ----

    /** Whether Bluetooth still shares the phone's main output (hardware offload). Uses root. */
    fun offload(): Offload {
        val output = RootRouting.runAsRoot(
            "getprop ro.bluetooth.a2dp_offload.supported; getprop persist.bluetooth.a2dp_offload.disabled"
        ).getOrNull() ?: return Offload.UNKNOWN
        val lines = output.lines().map { it.trim() }
        val supported = lines.getOrNull(0) == "true"
        val disabled = lines.getOrNull(1) == "true"
        return when {
            !supported -> Offload.NOT_USED
            disabled -> Offload.OFF
            else -> Offload.ON
        }
    }

    /** Starts the hub, or hands a running one the current choice of apps. */
    fun start(context: Context): Result<Status> = runCatching {
        writeConfig(context)
        val version = versionCode(context)
        val current = readStatus(context)
        if (current != null && current.isRunning) {
            if (current.version == version) return@runCatching current
            // A hub from before an app update: start the new one
            stop(context).getOrThrow()
        }
        File(context.filesDir, STOP).delete()

        val startedAt = System.currentTimeMillis()
        val apk = context.applicationInfo.sourceDir
        val dir = context.filesDir.absolutePath
        // setsid and nohup: the hub keeps running after this shell and the app are gone
        val command = "S=; command -v setsid >/dev/null 2>&1 && S=setsid; " +
            "CLASSPATH='$apk' nohup \$S /system/bin/app_process /system/bin " +
            "${RoutingHelper::class.java.name} hub '$dir' $version " +
            "</dev/null >$LOG_FILE 2>&1 &"
        RootRouting.runAsRoot(command).getOrThrow()

        repeat(80) {
            Thread.sleep(100)
            val status = readStatus(context)
            if (status != null && status.time >= startedAt && status.state != "starting") {
                return@runCatching status
            }
        }
        throw IllegalStateException("the hub did not start, see diagnostics")
    }

    /** Stops the hub and puts the phone's normal routing back. */
    fun stop(context: Context): Result<Unit> = runCatching {
        val before = readStatus(context)
        File(context.filesDir, STOP).writeText("stop\n")
        var last = before
        if (before != null && before.isRunning) {
            for (i in 0 until 80) {
                Thread.sleep(100)
                last = readStatus(context)
                if (last == null || !last.isRunning) break
            }
            if (last != null && last.isRunning) {
                RootRouting.runAsRoot("kill -9 ${last.pid}")
            }
        }
        // A hub that crashed or had to be killed leaves the phone preference and the
        // "apps play side by side" setting behind: reset them
        if (last != null && last.state != "stopped") {
            RootRouting.runHelper(context, HELPER_HUB_RELEASE).getOrThrow()
        }
    }

    private fun versionCode(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()

    // ---- notification ----

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.hub_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showNotification(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, HubStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.hub_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.hub_stop_action), stop)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    // ---- diagnostics ----

    fun collectDiagnostics(context: Context, rootAvailable: Boolean): String = buildString {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("AudioPipe ${pkg.versionName}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Root: $rootAvailable")
        val packages = selectedPackages(context)
        appendLine("Apps for the speaker: " + packages.joinToString { "$it (${uidsFor(context, setOf(it)).firstOrNull()})" })
        appendLine()

        appendLine("== Hub status file ==")
        appendLine(runCatching { File(context.filesDir, STATUS).readText().trim() }.getOrElse { "none: $it" })
        val status = readStatus(context)
        if (status != null) {
            appendLine("age ${(System.currentTimeMillis() - status.time) / 1000} s, running ${status.isRunning}")
        }
        appendLine()

        if (!rootAvailable) {
            appendLine("No root, so no system details.")
            return@buildString
        }

        appendLine("== Settings ==")
        appendLine(
            RootRouting.runAsRoot(
                "echo a2dp offload supported: \$(getprop ro.bluetooth.a2dp_offload.supported); " +
                    "echo a2dp offload disabled: \$(getprop persist.bluetooth.a2dp_offload.disabled); " +
                    "echo playback offload disabled: \$(getprop audio.offload.disable); " +
                    "echo deep buffer media: \$(getprop audio.deep_buffer.media); " +
                    "echo multi audio focus: \$(settings get system multi_audio_focus_enabled)"
            ).fold({ it }, { RootRouting.describe(it) }).trim()
        )
        appendLine()

        appendLine("== Hub log ==")
        appendLine(RootRouting.runAsRoot("tail -n 60 $LOG_FILE").fold({ it }, { RootRouting.describe(it) }).trim())
        appendLine()

        appendLine("== Routing ==")
        appendLine(RootRouting.runHelper(context, "status").fold({ it }, { RootRouting.describe(it) }).trim())
        appendLine()

        appendLine("== Audio policy mixes and outputs ==")
        appendLine(
            RootRouting.runAsRoot(
                "dumpsys media.audio_policy | grep -E 'Primary Output I/O handle|Audio Policy Mix|- mix type|- Route Flags|- device type|- device address|- output:|Criterion|Output [0-9]+ dump|Module [0-9]|Devices|Flags' " +
                    "| head -n 120"
            ).fold({ it }, { RootRouting.describe(it) }).trim()
        )
        appendLine()

        appendLine("== Bluetooth ==")
        appendLine(
            RootRouting.runAsRoot("dumpsys bluetooth_manager | grep -iE 'active ?device|a2dp|le ?audio' | head -n 20")
                .fold({ it }, { RootRouting.describe(it) }).trim()
        )
    }
}
