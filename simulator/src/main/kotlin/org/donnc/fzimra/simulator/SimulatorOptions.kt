package org.donnc.fzimra.simulator

import java.math.BigDecimal
import java.time.Duration

/** A tax the simulated taxpayer may use. `percent` is `null` for an exempt tax. */
public data class SimulatedTax(val taxId: Int, val name: String, val percent: BigDecimal?)

/**
 * The simulated taxpayer and gateway.
 *
 * @property vatNumber `null` for a taxpayer not registered for VAT.
 * @property activationKeys keys accepted for registration; any 8 characters when empty.
 * @property futureTolerance how far ahead of the simulator's clock a receipt
 *   may be dated before it is flagged RCPT031.
 * @property closeValidation how long ZIMRA takes to validate a close.
 */
public data class SimulatorOptions(
    val taxpayerName: String = "Simulated Taxpayer (Pvt) Ltd",
    val taxpayerTin: String = "2000000000",
    val vatNumber: String? = "220000000",
    val branchName: String = "Simulator Branch",
    val taxes: List<SimulatedTax> = listOf(
        SimulatedTax(1, "Standard rated 15%", BigDecimal("15")),
        SimulatedTax(2, "Zero rated 0%", BigDecimal.ZERO),
        SimulatedTax(3, "Exempt", null),
    ),
    val dayMaxHours: Int = 24,
    val dayEndNotificationHours: Int = 2,
    val qrUrl: String = "https://fdmstest.zimra.co.zw",
    val activationKeys: List<String> = emptyList(),
    val futureTolerance: Duration = Duration.ofMinutes(5),
    val closeValidation: Duration = Duration.ofSeconds(2),
    val certificateValidity: Duration = Duration.ofDays(365),
)

/**
 * Faults to inject, each used up as it fires, so a test or a demo asks for
 * exactly what it wants. Safe to change from any thread.
 */
public class SimulatorFaults {
    /** Requests that fail before reaching the gateway, as if offline. */
    @Volatile public var dropConnections: Int = 0

    /** Stay offline until set back to `false`. */
    @Volatile public var offline: Boolean = false

    /** Requests answered with HTTP 503. */
    @Volatile public var serverErrors: Int = 0

    /** Receipts accepted whose answer is then lost. */
    @Volatile public var dropAfterSubmit: Int = 0

    /** Added to the time on each answer, as a gateway with a wrong clock would. */
    @Volatile public var dateSkew: Duration = Duration.ZERO

    /** A finding (code to colour, such as `"RCPT025" to "Red"`) for the next accepted receipt. */
    @Volatile public var flagNextReceipt: Pair<String, String>? = null

    /** The error code the next close fails with. */
    @Volatile public var refuseNextClose: String? = null

    /**
     * Report a refused close as the day opened again with an error code, as
     * some gateways do, rather than as a failed close.
     */
    @Volatile public var refuseCloseAsReopened: Boolean = false

    /** Clears every fault. */
    public fun clear() {
        dropConnections = 0
        offline = false
        serverErrors = 0
        dropAfterSubmit = 0
        dateSkew = Duration.ZERO
        flagNextReceipt = null
        refuseNextClose = null
        refuseCloseAsReopened = false
    }
}
