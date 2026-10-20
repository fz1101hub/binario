package im.binario.companion.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/** Input vocale fornito dal sistema Wear OS: Binario non registra né persiste l'audio o il transcript. */
class BinarioVoiceRequestActivity : Activity() {
  private var tts: TextToSpeech? = null
  private val handler = Handler(Looper.getMainLooper())
  private val voicePreferences by lazy { getSharedPreferences("binario_wear_voice_preferences_v1", Context.MODE_PRIVATE) }
  private var waitingForRealtime = false
  private fun touchTargetPx() = (48 * resources.displayMetrics.density).toInt()
  private fun resolvedLocale(): Locale {
    val configured = resources.configuration.locales[0]
    return configured.takeIf { it.language.lowercase(Locale.ROOT) in setOf("it", "en", "de", "fr", "es") } ?: Locale.ITALIAN
  }
  private val replyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == replyAction && waitingForRealtime) {
        waitingForRealtime = false
        handler.removeCallbacksAndMessages(null)
        showAnswer(intent.getStringExtra(replyExtra) ?: getString(R.string.binario_unavailable))
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = resolvedLocale() }
    val filter = IntentFilter(replyAction)
    ContextCompat.registerReceiver(this, replyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    launchSystemVoiceInput()
  }
  private fun launchSystemVoiceInput() {
    try {
      startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, resolvedLocale().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.binario_voice_prompt))
      }, companionRequest)
    } catch (_: Exception) { showAnswer(getString(R.string.binario_dictation_unavailable)) }
  }
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    val transcript = if (requestCode == companionRequest && resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty() else ""
    if (transcript.isEmpty()) showLocalActions() else if (asksRealtime(transcript)) requestFreshRealtimeAnswer(transcript) else showAnswer(answerFromFreshLocalState(transcript))
  }
  private fun answerFromFreshLocalState(transcript: String): String {
    val state = BinarioWearStore.read(this)
    val locale = resolvedLocale()
    val question = transcript.lowercase(locale).trim().split(" ").filter { it.isNotBlank() }.joinToString(" ")
    val asksSavedJourney = getString(R.string.binario_saved_journey_keywords).split("|").any { question.contains(it) }
    val savedRoute = state.title.split("→").map { it.trim().lowercase(locale) }
    val asksCurrentSavedRoute = savedRoute.size == 2 && savedRoute.all { it.length >= 2 && question.contains(it) }
    return if ((asksSavedJourney || asksCurrentSavedRoute) && state.canComplicate) "${state.title}. ${state.status}" else getString(R.string.binario_unavailable)
  }
  private fun asksRealtime(transcript: String) = getString(R.string.binario_realtime_keywords).split("|").any { transcript.lowercase(resolvedLocale()).contains(it) }
  private fun requestFreshRealtimeAnswer(transcript: String) {
    val request = transcript.take(240).toByteArray(Charsets.UTF_8)
    if (request.isEmpty()) { showAnswer(getString(R.string.binario_unavailable)); return }
    waitingForRealtime = true
    showWaiting()
    Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
      val node = nodes.firstOrNull()
      if (node == null) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer(getString(R.string.binario_unavailable)); return@addOnSuccessListener }
      Wearable.getMessageClient(this).sendMessage(node.id, "/binario/companion/voice-request/v1", request).addOnFailureListener { if (waitingForRealtime) { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); showAnswer(getString(R.string.binario_unavailable)) } }
    }
    handler.postDelayed({ if (waitingForRealtime) { waitingForRealtime = false; showAnswer(getString(R.string.binario_unavailable)) } }, 12_000)
  }
  private fun showWaiting() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = getString(R.string.binario_checking); textSize = 18f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_checking_state); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_cancel); contentDescription = getString(R.string.binario_cancel_checking); setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); finish() } })
    })
  }
  private fun showLocalActions() {
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = getString(R.string.binario_no_voice); textSize = 17f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_no_voice_state); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_show_next); contentDescription = getString(R.string.binario_show_next_state); setOnClickListener { showAnswer(answerFromFreshLocalState(getString(R.string.binario_next_query))) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_retry); contentDescription = getString(R.string.binario_retry_state); setOnClickListener { launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_close); contentDescription = getString(R.string.binario_close_state); setOnClickListener { finish() } })
    })
  }
  private fun showAnswer(answer: String) {
    val speakAnswers = voicePreferences.getBoolean("speak_answers", false)
    setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
      addView(TextView(this@BinarioVoiceRequestActivity).apply { text = answer; textSize = 18f; gravity = Gravity.CENTER; contentDescription = getString(R.string.binario_answer_heading, answer); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_listen); contentDescription = getString(R.string.binario_listen); setOnClickListener { tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer") } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(if (speakAnswers) R.string.binario_disable_auto_read else R.string.binario_enable_auto_read); contentDescription = text; setOnClickListener { voicePreferences.edit().putBoolean("speak_answers", !speakAnswers).apply(); showAnswer(answer) } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_retry); contentDescription = getString(R.string.binario_retry_state); setOnClickListener { waitingForRealtime = false; handler.removeCallbacksAndMessages(null); launchSystemVoiceInput() } })
      addView(Button(this@BinarioVoiceRequestActivity).apply { minHeight = touchTargetPx(); minWidth = touchTargetPx(); text = getString(R.string.binario_close); contentDescription = getString(R.string.binario_close_state); setOnClickListener { finish() } })
    })
    if (speakAnswers) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "binario-voice-answer")
  }
  override fun onDestroy() { unregisterReceiver(replyReceiver); handler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); super.onDestroy() }
  companion object { const val companionRequest = 71; const val replyAction = "im.binario.companion.wear.VOICE_REPLY"; const val replyExtra = "binario_voice_reply" }
}
