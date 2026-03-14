package com.notifmirror.wear

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.gms.wearable.Wearable

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val settingsButton = findViewById<Button>(R.id.notifSettingsButton)
        val logButton = findViewById<Button>(R.id.viewLogButton)

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                statusText.text = "Connected to phone.\n\nMirrored notifications will appear automatically."
            } else {
                statusText.text = "Not connected to phone.\n\nMake sure your phone is nearby and the companion app is installed."
            }
        }.addOnFailureListener {
            statusText.text = "Could not check connection.\n\nMake sure Google Play Services is available."
        }

        settingsButton.setOnClickListener {
            try {
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.google.android.wearable.app",
                        "com.google.android.clockwork.home.notificationsettings.NotificationSettingsPreferenceActivity"
                    )
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open notification settings", Toast.LENGTH_SHORT).show()
            }
        }

        logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        val versionText = findViewById<TextView>(R.id.versionText)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            versionText.text = "v${pInfo.versionName}"
        } catch (_: Exception) {}
    }
}
