package org.donnc.fzimra.example.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.donnc.fzimra.core.FdmsEnvironment

/** Where the example sends its fiscal data. */
enum class Mode(val title: String, val summary: String, val environment: FdmsEnvironment?) {
    SIMULATOR(
        "Simulator",
        "A ZIMRA gateway inside the app. No account needed, and every exchange is visible.",
        null,
    ),
    ZIMRA_TEST(
        "ZIMRA test",
        "ZIMRA's test environment, with a test device ZIMRA issued to you.",
        FdmsEnvironment.TEST,
    ),
    ZIMRA_PRODUCTION(
        "ZIMRA production",
        "Live fiscalisation. Receipts are real tax records.",
        FdmsEnvironment.PRODUCTION,
    ),
}

/** How the device is known to ZIMRA. */
data class DeviceSettings(
    val deviceId: Int,
    val serialNo: String,
    val modelName: String,
    val modelVersion: String,
) {
    val isComplete: Boolean
        get() = deviceId > 0 && serialNo.isNotBlank() && serialNo.length <= 20 && modelName.isNotBlank() && modelVersion.isNotBlank()

    companion object {
        /** The simulator needs no real device: this one is ready to use. */
        val SIMULATED = DeviceSettings(100_001, "SIM-0001", "fzimra-example", "0.1.0")
        val BLANK = DeviceSettings(0, "", "", "")
    }
}

data class ExampleSettings(val mode: Mode, private val devices: Map<Mode, DeviceSettings>) {
    fun device(mode: Mode = this.mode): DeviceSettings = devices[mode] ?: DeviceSettings.BLANK
}

private val Context.dataStore by preferencesDataStore("example-settings")

/** The example's settings, kept with DataStore. */
class SettingsRepository(private val context: Context) {
    private val modeKey = stringPreferencesKey("mode")

    val settings: Flow<ExampleSettings> = context.dataStore.data.map { p ->
        ExampleSettings(
            mode = p[modeKey]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SIMULATOR,
            devices = Mode.entries.associateWith { mode -> if (mode == Mode.SIMULATOR) DeviceSettings.SIMULATED else p.device(mode) },
        )
    }

    suspend fun current(): ExampleSettings = settings.first()

    suspend fun setMode(mode: Mode) {
        context.dataStore.edit { it[modeKey] = mode.name }
    }

    suspend fun setDevice(mode: Mode, device: DeviceSettings) {
        require(mode != Mode.SIMULATOR) { "the simulated device is fixed" }
        context.dataStore.edit {
            it[intPreferencesKey("${mode.name}.deviceId")] = device.deviceId
            it[stringPreferencesKey("${mode.name}.serialNo")] = device.serialNo.trim()
            it[stringPreferencesKey("${mode.name}.modelName")] = device.modelName.trim()
            it[stringPreferencesKey("${mode.name}.modelVersion")] = device.modelVersion.trim()
        }
    }

    private fun Preferences.device(mode: Mode) = DeviceSettings(
        deviceId = this[intPreferencesKey("${mode.name}.deviceId")] ?: 0,
        serialNo = this[stringPreferencesKey("${mode.name}.serialNo")].orEmpty(),
        modelName = this[stringPreferencesKey("${mode.name}.modelName")].orEmpty(),
        modelVersion = this[stringPreferencesKey("${mode.name}.modelVersion")].orEmpty(),
    )
}
