package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** Input vocale fornito dal sistema Wear OS: Binario non registra né persiste l'audio o il transcript. */
class BinarioVoiceRequestActivity : Activity() {
  private var tts: TextToSpeech? = null
  private val handler = Handler(Looper.getMainLooper())
  private val voicePreferences by lazy { getSharedPreferences("binario_wear_voice_preferences_v1", Context.MODE_PRIVATE) }
  private var waitingForRealtime = false
  private fun touchTargetPx() = (48 * resources.displayMetrics.density).toInt()
  private fun announceForAccessibility(message: String) { window.decorView.post { window.decorView.announceForAccessibility(message) } }
  private val replyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == replyAction && waitingForRealtime) {
        waitingForRealtime = false
        handler.removeCallbacksAndMessages(null)
        showAnswer(intent.getStringExtra(replyExtra) ?: "Dati non disponibili in tempo reale.")
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.ITALIAN }
    val filter = IntentFilter(replyAction)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(replyReceiver, filter)
    launchSystemVoiceInput()
  }
  private fun launchSystemVoiceInput() {
    try {
      startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Chiedi a Binario")
      }, companionRequest)
    } catch (_: Exception) { showAnswer("Dettatura di sistema non disponibile.") }
  }
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val transcript = if (requestCode == companionRequest && resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty() else ""
    if (transcript.isEmpty()) showLocalActions() else if (asksRealtime(transcript)) requestFreshRealtimeAnswer(transcript) else showAnswer(answerFromFreshLocalState(transcript))
  }
  private fun answerFromFreshLocalState(transcript: String): String {
    val state = BinarioWearStore.read(this)
    val question = transcript.lowercase(Locale.ITALIAN).trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
    val asksSavedJourney = listOf("prossima", "prossimo", "mio viaggio", "mia tratta", "a che ora parte").any { question.contains(it) }
    val savedRoute = state.title.split("→").map { it.trim().lowercase(Locale.ITALIAN) }
    val asksCurrentSavedRoute = savedRoute.size == 2 && savedRoute.all { it.length >= 2 && question.contains(it) }
    return if ((asksSavedJourney || asksCurrentSavedRoute) && state.canComplicate) "${state.title}. ${state.status}" else "Dati non disponibili in tempo reale."
  }
  private fun asksRealtime(transcript: String) = listOf("ritard", "binari", "cancell", "avvis", "realtime").any { transcript.lowercase(Locale.ITALIAN).contains(it) }
  private fun requestFreshRealtimeAnswer(transcript: String) {
    val request = transcript.take(240).toByteArray(Charsets.UTF_8)
    if (request.isEmpty()) { showAnswer("Dati non disponibili in tempo reale."); return }
    waitingForRealtime = true
    showWaiting()
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer("Dati non disponibili in tempo reale."); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/voice-request/v1", request).addOnFailureListener { if (waitingForRealtime) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer("Dati non disponibili in tempo reale.") } }
    }
    handler.postDelayed({ if (waitingForRealtime) { waitingForRealtime = false; showAnswer("Dati non disponibili in tempo reale.") } }, 12_000)
  }
  private fun showWaiting() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = "Verifica in corso…"; textSize = 18f; gravity = Gravity.CENTER; contentDescription = text })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Annulla"; contentDescription = "Annulla verifica"; setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); finish() } })
    })
    announceForAccessibility("Verifica in corso. È disponibile il pulsante Annulla.")
  }
  private fun showLocalActions() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = "Nessuna richiesta vocale acquisita."; textSize = 17f; gravity = Gravity.CENTER; contentDescription = text })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Mostra prossima tratta"; contentDescription = "Mostra la prossima tratta programmata"; setOnClickListener { showAnswer(answerFromFreshLocalState("prossima tratta")) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Riprova"; contentDescription = "Riprova richiesta vocale"; setOnClickListener { launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Chiudi"; contentDescription = "Chiudi richiesta Binario"; setOnClickListener { finish() } })
    })
    announceForAccessibility("Nessuna richiesta vocale acquisita. Puoi mostrare la prossima tratta o riprovare.")
  }
  private fun showAnswer(answer: String) {
    val speakAnswers = voicePreferences.getBoolean("speak_answers", false)
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = answer; textSize = 18f; gravity = Gravity.CENTER; contentDescription = answer })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Ascolta risposta"; contentDescription = "Ascolta risposta"; setOnClickListener { tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer") } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = if (speakAnswers) "Disattiva lettura automatica" else "Attiva lettura automatica"; contentDescription = text; setOnClickListener { voicePreferences.edit().putBoolean("speak_answers", !speakAnswers).apply(); showAnswer(answer) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Riprova"; contentDescription = "Riprova richiesta vocale"; setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = "Chiudi"; contentDescription = "Chiudi richiesta Binario"; setOnClickListener { finish() } })
    })
    announceForAccessibility("Risposta Binario. $answer")
    if (speakAnswers) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer")
  }
  override fun onDestroy() { unregisterReceiver(replyReceiver); handler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); super.onDestroy() }
  companion object { const val companionRequest = 71; const val replyAction = "im.binario.companion.wear.VOICE_REPLY"; const val replyExtra = "binario_voice_reply" }
}
