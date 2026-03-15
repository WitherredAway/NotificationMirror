package com.notifmirror.wear

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result received, continue
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Don't use DynamicColors on watch — use hardcoded colors matching phone app
        setContentView(R.layout.activity_main)

        // Rotary/bezel scrolling support
        val scrollView = findViewById<ScrollView>(R.id.mainScrollView)
        scrollView.requestFocus()
        scrollView.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                v.scrollBy(0, (delta * dpToPx(40)).toInt())
                true
            } else {
                false
            }
        }

        val statusCard = findViewById<LinearLayout>(R.id.statusCard)
        val statusIcon = findViewById<ImageView>(R.id.statusIcon)
        val statusText = findViewById<TextView>(R.id.statusText)

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                statusCard.setBackgroundResource(R.drawable.bg_status_active)
                statusIcon.setImageResource(R.drawable.ic_check_circle)
                statusText.text = "Connected to phone"
            } else {
                statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
                statusIcon.setImageResource(R.drawable.ic_error_circle)
                statusText.text = "Not connected to phone"
            }
        }.addOnFailureListener {
            statusCard.setBackgroundResource(R.drawable.bg_status_inactive)
            statusIcon.setImageResource(R.drawable.ic_error_circle)
            statusText.text = "Connection check failed"
        }

        findViewById<LinearLayout>(R.id.notifSettingsButton).setOnClickListener {
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

        findViewById<LinearLayout>(R.id.phoneSettingsButton).setOnClickListener {
            scope.launch {
                try {
                    val nodeClient = Wearable.getNodeClient(this@MainActivity)
                    val nodes = nodeClient.connectedNodes.await()
                    if (nodes.isEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "No phone connected", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    for (node in nodes) {
                        Wearable.getMessageClient(this@MainActivity)
                            .sendMessage(node.id, "/open_settings", ByteArray(0))
                            .await()
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Opening settings on phone", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("NotifMirrorWear", "Failed to open phone settings", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to reach phone", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.viewLogButton).setOnClickListener {
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

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
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
