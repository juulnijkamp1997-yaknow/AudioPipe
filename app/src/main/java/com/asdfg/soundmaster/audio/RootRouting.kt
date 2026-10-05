package com.asdfg.soundmaster.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import com.asdfg.soundmaster.root.RoutingHelper

/**
 * "Keep TalkBack and notifications on the phone": runs [RoutingHelper] as root to change
 * system routing, and remembers whether the user turned it on.
 *
 * All functions that run commands block; call them from a background thread.
 */
object RootRouting {

    private const val PREFS = "routing"
    private const val KEY_KEEP_ON_PHONE = "keep_on_phone"

    const val ACTION_KEEP_ON_PHONE = "keep-on-phone"
    const val ACTION_RELEASE = "release"
    private const val ACTION_STATUS = "status"
    private const val ACTION_DEVICES = "devices"

    fun isKeepOnPhoneEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ON_PHONE, false)

    fun setKeepOnPhoneEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_KEEP_ON_PHONE, enabled)
            .apply()
    }

    /**
     * Runs a [RoutingHelper] command as root, or as [asUid] (through su) to see what a fresh
     * process with that uid's permissions sees. Fails unless the helper reports "RESULT OK".
     */
    fun runHelper(context: Context, action: String, asUid: Int? = null): Result<String> {
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
            "${RoutingHelper::class.java.name} $action"
        return su(command, asUid).mapCatching { output ->
            val result = output.lineSequence().lastOrNull { it.startsWith("RESULT ") }
            if (result == "RESULT OK") output else throw RoutingException(result ?: "no result", output)
        }
    }

    /** Text the user can copy into a chat: what the app and root see, and the Bluetooth state. */
    fun collectDiagnostics(context: Context, rootAvailable: Boolean): String = buildString {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val nearbyDevices = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        appendLine("AudioPipe ${pkg.versionName}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Root: $rootAvailable, keep-on-phone setting: ${isKeepOnPhoneEnabled(context)}")
        appendLine("Nearby devices permission: $nearbyDevices, routing one app: ${SoundMasterService.running}")
        appendLine()

        appendLine("== Outputs seen by the app ==")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            appendLine("type ${device.type} id ${device.id} name ${device.productName} in list ${OutputDevices.isRoutable(device)}")
        }
        appendLine()

        if (!rootAvailable) {
            appendLine("No root, so no system details.")
            return@buildString
        }

        // Same uid and permissions as the app, but a fresh process: tells apart a permission
        // filter (Bluetooth missing here too) from a stale device list inside the app
        appendLine("== Fresh process with the app's uid ==")
        appendLine(
            runHelper(context, ACTION_DEVICES, asUid = context.applicationInfo.uid)
                .fold({ it }, { describe(it) }).trim()
        )
        appendLine()

        appendLine("== Root helper ==")
        appendLine(runHelper(context, ACTION_STATUS).fold({ it }, { describe(it) }).trim())
        appendLine()

        appendLine("== Bluetooth offload ==")
        appendLine(
            su(
                "echo a2dp offload supported: \$(getprop ro.bluetooth.a2dp_offload.supported); " +
                    "echo a2dp offload disabled: \$(getprop persist.bluetooth.a2dp_offload.disabled)"
            ).fold({ it }, { describe(it) }).trim()
        )
        appendLine()

        appendLine("== Audio policy ==")
        appendLine(
            su(
                "dumpsys media.audio_policy | grep -E 'AUDIO_DEVICE_OUT_|Output|I/O handle|Devices|Flags|Active' " +
                    "| head -n 80"
            ).fold({ it }, { describe(it) }).trim()
        )
        appendLine()

        appendLine("== Bluetooth ==")
        appendLine(
            su("dumpsys bluetooth_manager | grep -iE 'active ?device|a2dp|le ?audio' | head -n 30")
                .fold({ it }, { describe(it) }).trim()
        )
    }

    /** Short reason for the status line, full helper output for diagnostics. */
    fun describe(error: Throwable): String =
        if (error is RoutingException) "${error.message}\n${error.output}" else error.toString()

    fun shortReason(error: Throwable): String =
        (error.message ?: error.toString()).removePrefix("RESULT FAILED: ").lineSequence().first().take(200)

    /** One-off root shell command, for example an appops change. */
    fun runAsRoot(command: String): Result<String> = su(command)

    private fun su(command: String, asUid: Int? = null): Result<String> = try {
        // Magisk su: options first, then the user (a uid) to switch to
        val suCommand = if (asUid == null) listOf("su", "-c", command) else listOf("su", "-c", command, asUid.toString())
        val process = ProcessBuilder(suCommand).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        // grep exits 1 when nothing matched; the helper reports its own result line
        if (exitCode == 0 || exitCode == 1 || output.contains("RESULT ")) {
            Result.success(output)
        } else {
            Result.failure(RoutingException("su exited with code $exitCode", output))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    class RoutingException(message: String, val output: String) : Exception(message)
}
