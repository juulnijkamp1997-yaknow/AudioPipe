package com.asdfg.soundmaster.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** The Stop button in the hub's notification. */
class HubStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                HubControl.stop(appContext).onFailure { Log.e(TAG, "Could not stop the hub", it) }
                HubControl.setWasOn(appContext, false)
                HubControl.cancelNotification(appContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "HubStopReceiver"
    }
}
