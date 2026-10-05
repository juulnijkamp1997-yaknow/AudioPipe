package com.asdfg.soundmaster.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Runs in its own process (":probe" in the manifest) and reports the output devices that a
 * freshly started process of this app sees. Android keeps the device list per process, and on
 * Android 17 the main process's copy was seen missing a Bluetooth speaker that a fresh process
 * does list. Comparing the two in the diagnostics shows whether that list went stale.
 *
 * The probe process exits shortly after answering, so every call gets a fresh process.
 */
class DeviceProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_DEVICES) return null
        val audioManager = context!!.getSystemService(AudioManager::class.java)
        val text = buildString {
            appendLine("pid ${Process.myPid()} uid ${Process.myUid()}")
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
                appendLine("type ${device.type} id ${device.id} name ${device.productName}")
            }
        }
        // Callers hold only an unstable reference (see query), so this does not take them down
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 2000)
        return Bundle().apply { putString(KEY_TEXT, text) }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val METHOD_DEVICES = "devices"
        private const val KEY_TEXT = "text"

        /**
         * Blocking. Uses an unstable provider reference on purpose: with a stable one, Android
         * kills the calling process too when the probe process exits.
         */
        fun query(context: Context): String {
            val authority = "${context.packageName}.probe"
            val client = context.contentResolver.acquireUnstableContentProviderClient(authority)
                ?: return "probe not available"
            return try {
                client.call(METHOD_DEVICES, null, null)?.getString(KEY_TEXT) ?: "no answer"
            } catch (e: Exception) {
                "probe failed: $e"
            } finally {
                client.close()
            }
        }
    }
}
