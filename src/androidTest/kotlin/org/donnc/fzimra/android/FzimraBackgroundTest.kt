package org.donnc.fzimra.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptRequest
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.TaxDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/** Runs on a device or emulator: `./gradlew connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class FzimraBackgroundTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val deviceId = (System.nanoTime() % 1_000_000).toInt() + 1
    private val alias = "fzimra-test-background-$deviceId"
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @After
    fun cleanUp() {
        FzimraBackground.cancel(context, deviceId)
        KeystoreSigner.delete(alias)
        KeystoreSealer.delete(Fzimra.sealAlias(deviceId))
        File(context.noBackupFilesDir, "org.donnc.fzimra/state/fzimra.state.$deviceId").delete()
        File(context.noBackupFilesDir, "org.donnc.fzimra/records").listFiles()
            ?.filter { it.name.startsWith("fzimra.record.$deviceId.") }?.forEach { it.delete() }
    }

    @Test
    fun aSaleSchedulesANetworkBoundPersistedSync() {
        val configuration = DeviceConfiguration(
            deviceId, "https://fdmstest.zimra.co.zw",
            listOf(TaxDefinition(1, "Standard rated 15%", BigDecimal("15"))), isVatPayer = true, dayMaxHours = 24,
        )
        val device = Fzimra.device(context, configuration, KeystoreSigner.getOrCreate(alias, requireHardware = false))
        assertNull(scheduler.getPendingJob(FzimraBackground.jobId(deviceId)))
        device.issue(
            ReceiptRequest(
                ReceiptType.FISCAL_INVOICE, "USD", "INV-$deviceId",
                listOf(ReceiptLine.sale("Bread", 1, 1.15, taxId = 1, hsCode = "19059000")), listOf(Payment.cash()),
            ),
        )
        val job = scheduler.getPendingJob(FzimraBackground.jobId(deviceId))
        assertNotNull("a sync was scheduled", job)
        assertEquals(deviceId, job!!.extras.getInt(FzimraBackground.EXTRA_DEVICE_ID))
        assertTrue(job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)

        FzimraBackground.cancel(context, deviceId)
        assertNull(scheduler.getPendingJob(FzimraBackground.jobId(deviceId)))
    }
}
