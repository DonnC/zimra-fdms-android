package org.donnc.fzimra.example.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.donnc.fzimra.core.TaxpayerInformation
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.settings.DeviceSettings
import org.donnc.fzimra.example.settings.Mode
import org.donnc.fzimra.example.ui.friendly

data class SetupUi(
    val form: DeviceSettings = DeviceSettings.BLANK,
    val deviceIdText: String = "",
    val activationKey: String = "",
    val taxpayer: TaxpayerInformation? = null,
    val busy: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class SetupViewModel(private val engine: FiscalEngine) : ViewModel() {
    private val mutable = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = mutable

    /** Fills the form with what is saved for the selected mode. */
    fun load(device: DeviceSettings) {
        mutable.update { it.copy(form = device, deviceIdText = if (device.deviceId > 0) device.deviceId.toString() else "", taxpayer = null, error = null) }
    }

    fun edit(form: DeviceSettings, deviceIdText: String) {
        val digits = deviceIdText.filter(Char::isDigit).take(9)
        mutable.update { it.copy(form = form.copy(deviceId = digits.toIntOrNull() ?: 0), deviceIdText = digits, taxpayer = null, error = null) }
    }

    fun editKey(key: String) = mutable.update { it.copy(activationKey = key.take(8).uppercase(), taxpayer = null, error = null) }

    fun dismissMessage() = mutable.update { it.copy(message = null, error = null) }

    fun selectMode(mode: Mode) = run("Switching") { engine.setMode(mode) }

    /** Saves the form, then asks ZIMRA who the device belongs to. */
    fun verify(mode: Mode) = run("Asking ZIMRA who this device belongs to") {
        engine.saveDevice(mode, mutable.value.form)
        val taxpayer = engine.verifyTaxpayer(mutable.value.activationKey)
        mutable.update { it.copy(taxpayer = taxpayer) }
    }

    fun register(activationKey: String = mutable.value.activationKey) = run("Registering the device with ZIMRA") {
        engine.register(activationKey)
        mutable.update { it.copy(taxpayer = null, activationKey = "", message = "The device is registered and ready to sell.") }
    }

    /** The simulator accepts any eight-character activation key. */
    fun setUpSimulator() = run("Setting up the simulated device") {
        engine.verifyTaxpayer(SIMULATOR_KEY)
        engine.register(SIMULATOR_KEY)
        mutable.update { it.copy(message = "The simulated device is ready. Make a sale!") }
    }

    fun refreshConfiguration() = run("Fetching ZIMRA's configuration") {
        engine.refreshConfiguration()
        mutable.update { it.copy(message = "Configuration updated from ZIMRA.") }
    }

    fun renewCertificate() = run("Renewing the certificate") {
        engine.renewCertificate()
        mutable.update { it.copy(message = "A new certificate is installed.") }
    }

    fun reset() = run("Removing the device from this phone") {
        engine.resetDevice()
        mutable.update { it.copy(message = "The device was removed from this phone.") }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(busy = label, error = null, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.friendly()) }
            } finally {
                mutable.update { it.copy(busy = null) }
            }
        }
    }

    private companion object {
        const val SIMULATOR_KEY = "SIMULATE"
    }
}
