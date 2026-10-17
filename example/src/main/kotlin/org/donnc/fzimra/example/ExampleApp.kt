package org.donnc.fzimra.example

import android.app.Application
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.fiscal.TrafficLog
import org.donnc.fzimra.example.settings.SettingsRepository

/**
 * Creating the engine here creates the terminal at app start, which is all
 * background sync needs: the platform may start the app just to sync.
 */
class ExampleApp : Application() {
    lateinit var engine: FiscalEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = FiscalEngine(this, SettingsRepository(this), TrafficLog())
    }
}
