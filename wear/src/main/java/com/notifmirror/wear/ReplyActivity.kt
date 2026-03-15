package com.notifmirror.wear

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.RemoteInput
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class ReplyActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotifMirrorReply"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notifKey = intent.getStringExtra(NotificationHandler.EXTRA_NOTIF_KEY)
        val notifId = intent.getIntExtra(NotificationHandler.EXTRA_NOTIFICATION_ID, -1)
        val actionIndex = intent.getIntExtra(NotificationHandler.EXTRA_ACTION_INDEX, 0)

        // Check if we got a reply from RemoteInput (inline reply from notification)
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        if (remoteInputResults != null) {
            val replyText = remoteInputResults.getCharSequence(
                NotificationHandler.KEY_REPLY
            )?.toString()

            if (replyText != null && notifKey != null) {
                sendReply(notifKey, replyText, notifId, actionIndex)
            }
            finish()
            return
        }

        // Fallback: show a text input UI for manual reply
        // Don't use DynamicColors on watch — use hardcoded colors matching phone app
        setContentView(R.layout.activity_reply)

        val titleText = findViewById<TextView>(R.id.replyTitle)
        val replyInput = findViewById<EditText>(R.id.replyInput)
        val sendButton = findViewById<Button>(R.id.sendButton)

        titleText.text = "Reply"

        sendButton.setOnClickListener {
            val text = replyInput.text.toString().trim()
            if (text.isNotEmpty() && notifKey != null) {
                sendReply(notifKey, text, notifId, actionIndex)
                finish()
            }
        }
    }

    private fun sendReply(notifKey: String, replyText: String, notifId: Int, actionIndex: Int) {
        val json = JSONObject().apply {
            put("key", notifKey)
            put("actionIndex", actionIndex)
            put("reply", replyText)
        }

        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@ReplyActivity)
                val nodes = nodeClient.connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@ReplyActivity)
                        .sendMessage(node.id, "/reply", json.toString().toByteArray())
                        .await()
                    Log.d(TAG, "Reply sent to phone via node: ${node.displayName}")
                }

                if (notifId >= 0) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
            }
        }
    }
}
