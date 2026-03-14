package com.notifmirror.wear

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.gms.wearable.Wearable

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result received, continue
    }

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

        findViewById<TextView>(R.id.githubLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/WitherredAway/NotificationMirror")))
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifPerm = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notifPerm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(notifPerm)) {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission Required")
                        .setMessage("This app needs notification permission to display mirrored notifications from your phone.")
                        .setPositiveButton("Grant") { _, _ ->
                            notificationPermissionLauncher.launch(notifPerm)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    notificationPermissionLauncher.launch(notifPerm)
                }
            }
        }
    }
}
