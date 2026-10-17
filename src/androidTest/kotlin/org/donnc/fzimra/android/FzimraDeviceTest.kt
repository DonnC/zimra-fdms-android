package org.donnc.fzimra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.FiscalSignature
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.Payment
import org.donnc.fzimra.core.ReceiptLine
import org.donnc.fzimra.core.ReceiptRequest
import org.donnc.fzimra.core.ReceiptSyncStatus
import org.donnc.fzimra.core.ReceiptType
import org.donnc.fzimra.core.SignatureStrings
import org.donnc.fzimra.core.TaxDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

/** Runs on a device or emulator: `./gradlew connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class FzimraDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val deviceId = (System.nanoTime() % 1_000_000).toInt() + 1
    private val alias = "fzimra-test-device-$deviceId"
    private val configuration = DeviceConfiguration(
        deviceId = deviceId,
        qrUrl = "https://fdmstest.zimra.co.zw",
        taxes = listOf(TaxDefinition(1, "Standard rated 15%", BigDecimal("15"))),
        isVatPayer = true,
        dayMaxHours = 24,
    )

    @After
    fun cleanUp() {
        KeystoreSigner.delete(alias)
        File(context.noBackupFilesDir, "org.donnc.fzimra/state/fzimra.state.$deviceId").delete()
        File(context.noBackupFilesDir, "org.donnc.fzimra/records").listFiles()
            ?.filter { it.name.startsWith("fzimra.record.$deviceId.") }?.forEach { it.delete() }
    }

    private fun sale() = ReceiptRequest(
        type = ReceiptType.FISCAL_INVOICE,
        currency = "USD",
        invoiceNo = "INV-$deviceId",
        lines = listOf(ReceiptLine.sale("Bread", 2, 1.15, taxId = 1, hsCode = "19059000")),
        payments = listOf(Payment.cash()),
    )

    @Test
    fun issuesWithTheKeystoreKeyAndDefaultStores() {
        val signer = KeystoreSigner.getOrCreate(alias, requireHardware = false)
        val device = Fzimra.device(context, configuration, signer)
        val receipt = (device.issue(sale()) as IssueResult.Issued).receipt
        val canonical = SignatureStrings.receipt(
            deviceId, receipt.receipt.type, receipt.receipt.currency, receipt.globalNo, receipt.date,
            receipt.receipt.total, receipt.receipt.taxes, receipt.previousReceiptHash,
        )
        assertTrue(FiscalSignature.verify(signer.publicKey, canonical, receipt.signature.signature))
        assertEquals(ReceiptSyncStatus.PENDING, device.records.find(deviceId, 1)?.status)

        // A new instance, as after an app restart, continues the numbering.
        val again = Fzimra.device(context, configuration, KeystoreSigner.load(alias)!!)
        assertEquals(2, (again.issue(sale()) as IssueResult.Issued).receipt.globalNo)
        assertEquals(listOf(1, 2), again.pendingReceipts().map { it.globalNo })
    }
}
