package im.binario.companion.wear

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Wearable

/** Il watch inoltra solo la richiesta di aprire l’Agenda; le voci checklist restano sul telefono. */
class BinarioChecklistRequestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val message = TextView(this).apply { text = "Apro la checklist sul telefono…"; textSize = 17f; setPadding(24, 24, 24, 24) }
    setContentView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(message) })
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      if (nodes.isEmpty()) { message.text = "Telefono non raggiungibile."; return@addOnSuccessListener }
      nodes.forEach { node -> Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/open-checklist/v1", ByteArray(0)) }
      message.text = "Checklist aperta sul telefono."
      message.announceForAccessibility(message.text)
    }.addOnFailureListener { message.text = "Non è possibile aprire il telefono ora."; message.announceForAccessibility(message.text) }
  }
}
