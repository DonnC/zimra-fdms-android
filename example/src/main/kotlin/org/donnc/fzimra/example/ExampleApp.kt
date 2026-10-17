package org.donnc.fzimra.example

import android.app.Application
import org.donnc.fzimra.android.FzimraBackground
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.fiscal.TrafficLog
import org.donnc.fzimra.example.settings.SettingsRepository

/** The app's single object graph: one engine for the whole process. */
class ExampleApp : Application() {
    lateinit var engine: FiscalEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = FiscalEngine(this, SettingsRepository(this), TrafficLog())
        // Background sync must be installed here: the platform may start the
        // app just to run a sync, before any screen exists.
        FzimraBackground.install(onReport = engine::onBackgroundReport) { _, deviceId -> engine.syncFor(deviceId) }
    }
}
