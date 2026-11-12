package it.binario.app

import android.app.Application
import it.binario.app.data.live.PersistedRefusals
import it.binario.app.data.live.SharedPreferencesRefusalPauseStore

/**
 * Only gives the public-source gates their store before any component runs, so that a refusal pause holds in the
 * processes Android starts for widgets, workers and alarms too (audit2). No other start-up work belongs here.
 */
class BinarioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PersistedRefusals.store = SharedPreferencesRefusalPauseStore(this)
    }
}
