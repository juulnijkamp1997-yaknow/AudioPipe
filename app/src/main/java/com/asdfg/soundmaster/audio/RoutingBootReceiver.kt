package com.asdfg.soundmaster.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.asdfg.soundmaster.hub.HubControl

/**
 * After a reboot the hub is off. Android does keep the "apps play side by side" setting, so
 * when the hub (or the old Speaker mode) was on at shutdown, switch that back to normal.
 */
class RoutingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val hubWasOn = HubControl.wasOn(context)
        val keepOnPhone = RootRouting.isKeepOnPhoneEnabled(context)
        if (!hubWasOn && !keepOnPhone) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                if (hubWasOn) {
                    RootRouting.runHelper(appContext, HubControl.HELPER_HUB_RELEASE)
                        .onFailure { Log.e(TAG, "Could not clean up after the hub", it) }
                    HubControl.setWasOn(appContext, false)
                }
                if (keepOnPhone) {
                    // Speaker mode is gone from the app: undo it instead of applying it again
                    RootRouting.runHelper(appContext, RootRouting.ACTION_RELEASE)
                        .onFailure { Log.e(TAG, "Could not undo Speaker mode", it) }
                    RootRouting.setKeepOnPhoneEnabled(appContext, false)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "RoutingBootReceiver"
    }
}
