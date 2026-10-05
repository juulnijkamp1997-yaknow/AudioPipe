package com.asdfg.soundmaster.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Routing preferences may not survive a reboot. When "keep TalkBack and notifications on the
 * phone" is on, apply it again after boot, so it works without opening the app first.
 */
class RoutingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!RootRouting.isKeepOnPhoneEnabled(context)) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                RootRouting.runHelper(appContext, RootRouting.ACTION_KEEP_ON_PHONE)
                    .onFailure { Log.e(TAG, "Could not apply routing after boot", it) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "RoutingBootReceiver"
    }
}
