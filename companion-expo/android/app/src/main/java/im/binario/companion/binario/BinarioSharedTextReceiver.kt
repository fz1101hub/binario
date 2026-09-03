package im.binario.companion.binario

import android.content.Context
import android.content.Intent
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.NativeModule
import com.facebook.react.ReactPackage
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager

/** Riceve solo testo scelto nel menu Condividi; non accetta file, URL da risolvere o MIME generici. */
object BinarioSharedTextStore {
  private const val preferences = "binario_pending_shared_text_v1"
  private const val keyText = "text"
  private const val maxCharacters = 4_000
  private const val eventName = "BinarioSharedTextReceived"
  private var reactContext: ReactApplicationContext? = null

  private fun normalize(raw: String?): String? {
    val text = raw?.replace("\u0000", "")?.trim() ?: return null
    return text.takeIf { it.isNotEmpty() && it.length <= maxCharacters }
  }

  fun attach(context: ReactApplicationContext) { reactContext = context }

  fun capture(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
    val text = normalize(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) ?: return
    context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().putString(keyText, text).apply()
    val current = reactContext?.takeIf { it.hasActiveCatalystInstance() } ?: return
    current.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, text)
  }

  fun consume(context: Context): String? {
    val prefs = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
    val text = normalize(prefs.getString(keyText, null))
    prefs.edit().remove(keyText).apply()
    return text
  }
}

class BinarioSharedTextReceiverModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  init { BinarioSharedTextStore.attach(context) }
  override fun getName() = "BinarioSharedTextReceiver"
  @ReactMethod fun consume(promise: Promise) { promise.resolve(BinarioSharedTextStore.consume(context)) }
  @ReactMethod fun addListener(eventName: String) = Unit
  @ReactMethod fun removeListeners(count: Int) = Unit
}

class BinarioSharedTextReceiverPackage : ReactPackage {
  override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = listOf(BinarioSharedTextReceiverModule(reactContext))
  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
