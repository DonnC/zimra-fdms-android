package org.donnc.fzimra.simulator

import android.content.Context
import org.donnc.fzimra.core.DeviceKey
import org.donnc.fzimra.core.FdmsTransport
import org.donnc.fzimra.core.Gateway
import java.io.File

/**
 * The built-in simulator as a terminal's gateway:
 *
 * ```kotlin
 * val terminal = Fzimra.terminal(context, device, SimulatorGateway.shared(context))
 * ```
 *
 * The device presents its installed certificate, as it would over mutual TLS.
 * Device keys held in software are accepted, so it works on emulators.
 */
public class SimulatorGateway(
    /** The simulator: use its `faults` to make it misbehave, and `device(id)` to see what it holds. */
    public val simulator: FdmsSimulator,
) : Gateway {
    override val name: String = "Simulator"
    override val baseUrl: String = FdmsSimulator.BASE_URL
    override val allowsSoftwareKeys: Boolean = true

    override fun transport(key: DeviceKey?): FdmsTransport = simulator.transport { key?.certificate }

    public companion object {
        @Volatile private var shared: SimulatorGateway? = null

        /** One simulator for the app, kept in its private no-backup storage. */
        public fun shared(context: Context): SimulatorGateway = shared ?: synchronized(this) {
            shared ?: SimulatorGateway(
                FdmsSimulator(store = SimulatorStore.InFile(File(context.applicationContext.noBackupFilesDir, "fzimra-simulator.json"))),
            ).also { shared = it }
        }
    }
}
