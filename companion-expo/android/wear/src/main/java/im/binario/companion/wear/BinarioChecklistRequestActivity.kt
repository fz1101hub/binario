package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import java.util.UUID

/** Il watch inoltra solo la richiesta di aprire l’Agenda; le voci checklist restano sul telefono. */
class BinarioChecklistRequestActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var message: TextView
  private val requestId = UUID.randomUUID().toString()
  private var waitingForAcknowledgement = true
  private val acknowledgementReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == acknowledgementAction && waitingForAcknowledgement && intent.getStringExtra(acknowledgementExtra) == requestId) {
        waitingForAcknowledgement = false
        handler.removeCallbacksAndMessages(null)
        message.text = getString(R.string.binario_checklist_opened)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ContextCompat.registerReceiver(this, acknowledgementReceiver, IntentFilter(acknowledgementAction), ContextCompat.RECEIVER_NOT_EXPORTED)
    message = TextView(this).apply { text = getString(R.string.binario_opening_checklist); textSize = 17f; setPadding(24, 24, 24, 24); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    setContentView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(message) })
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { failClosed(getString(R.string.binario_phone_unreachable)); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, requestPath, requestId.toByteArray(Charsets.UTF_8))
        .addOnSuccessListener { if (waitingForAcknowledgement) message.text = getString(R.string.binario_opening_checklist) }
        .addOnFailureListener { failClosed(getString(R.string.binario_cannot_open_phone)) }
    }.addOnFailureListener { failClosed(getString(R.string.binario_phone_unreachable)) }
    handler.postDelayed({ failClosed(getString(R.string.binario_cannot_open_phone)) }, acknowledgementTimeoutMillis)
  }

  private fun failClosed(copy: String) {
    if (!waitingForAcknowledgement) return
    waitingForAcknowledgement = false
    handler.removeCallbacksAndMessages(null)
    message.text = copy
  }

  override fun onDestroy() {
    unregisterReceiver(acknowledgementReceiver)
    handler.removeCallbacksAndMessages(null)
    super.onDestroy()
  }

  companion object {
    const val requestPath = "/binario/companion/open-checklist/v1"
    const val acknowledgementPath = "/binario/companion/open-checklist-ack/v1"
    const val acknowledgementAction = "im.binario.companion.wear.CHECKLIST_OPENED"
    const val acknowledgementExtra = "binario_checklist_request_id"
    const val acknowledgementTimeoutMillis = 30_000L
  }
}
