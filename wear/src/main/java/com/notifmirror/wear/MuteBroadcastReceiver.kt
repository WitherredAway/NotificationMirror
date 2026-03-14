package com.notifmirror.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MuteBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMirrorMute"
        const val EXTRA_PACKAGE_NAME = "extra_mute_package"
        const val EXTRA_DURATION = "extra_mute_duration"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val duration = intent.getIntExtra(EXTRA_DURATION, 30)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val muteManager = MuteManager(context)
        muteManager.muteApp(packageName, duration)

        val shortPkg = packageName.split(".").lastOrNull() ?: packageName
        Log.d(TAG, "Muted $shortPkg for $duration minutes")
        Toast.makeText(context, "Muted $shortPkg for ${duration}min", Toast.LENGTH_SHORT).show()

        // Dismiss the notification
        if (notifId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }
}
