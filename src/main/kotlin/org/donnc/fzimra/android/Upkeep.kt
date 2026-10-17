package org.donnc.fzimra.android

import org.donnc.fzimra.core.FiscalTerminal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs each terminal's upkeep (pings, certificate renewal) every minute while the app runs. */
internal object Upkeep {
    private val terminals = CopyOnWriteArrayList<FiscalTerminal>()
    private val timer by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "fzimra-upkeep").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ terminals.forEach { t -> runCatching { t.maintain() } } }, 5, 60, TimeUnit.SECONDS)
        }
    }

    fun start(terminal: FiscalTerminal) {
        terminals += terminal
        timer
    }
}
