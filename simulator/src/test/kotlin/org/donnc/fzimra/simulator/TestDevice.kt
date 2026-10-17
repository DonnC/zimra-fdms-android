package org.donnc.fzimra.simulator

import org.donnc.fzimra.core.DeviceCertificate
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.DeviceCsr
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FdmsClient
import org.donnc.fzimra.core.FiscalClock
import org.donnc.fzimra.core.FiscalDevice
import org.donnc.fzimra.core.FiscalSnapshot
import org.donnc.fzimra.core.FiscalStateStore
import org.donnc.fzimra.core.FiscalSync
import org.donnc.fzimra.core.ReceiptRecord
import org.donnc.fzimra.core.ReceiptRecordStore
import org.donnc.fzimra.core.Sealer
import org.donnc.fzimra.core.SignatureAlgorithm
import org.donnc.fzimra.core.Signer
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.LocalDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A device key in memory. Tests only. */
internal class TestSigner : Signer {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val privateKey: PrivateKey = keys.private
    override val publicKey: PublicKey = keys.public
    override val algorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_P256_SHA256
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }
}

internal class MemoryStateStore : FiscalStateStore {
    val snapshots = mutableMapOf<Int, FiscalSnapshot>()
    override fun read(deviceId: Int): FiscalSnapshot? = snapshots[deviceId]
    override fun write(snapshot: FiscalSnapshot) {
        snapshots[snapshot.state.deviceId] = snapshot
    }
}

internal class MemoryRecordStore : ReceiptRecordStore {
    private val records = sortedMapOf<String, ReceiptRecord>()
    private fun key(d: Int, day: Int, g: Int) = "$d.${day.toString().padStart(10, '0')}.${g.toString().padStart(10, '0')}"
    override fun save(record: ReceiptRecord) {
        records[key(record.receipt.deviceId, record.receipt.fiscalDayNo, record.receipt.globalNo)] = record
    }
    override fun find(deviceId: Int, fiscalDayNo: Int, globalNo: Int): ReceiptRecord? = records[key(deviceId, fiscalDayNo, globalNo)]
    override fun list(deviceId: Int): List<ReceiptRecord> = records.filterKeys { it.startsWith("$deviceId.") }.values.toList()
}

internal class TestSealer : Sealer {
    override fun seal(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256"))
        doFinal(data)
    }
}

/** A device registered with [simulator], with its own stores and clock. */
internal class TestDevice(
    val simulator: FdmsSimulator,
    val identity: DeviceIdentity = DeviceIdentity(4242, "SIM-TEST", "fzimra-simulator-test", "1.0"),
    var now: LocalDateTime = LocalDateTime.of(2026, 10, 17, 8, 30),
) {
    val signer = TestSigner()
    var certificate: X509Certificate? = null
    val client: FdmsClient = simulator.client(identity, { certificate })
    val states = MemoryStateStore()
    val records = MemoryRecordStore()
    lateinit var device: FiscalDevice

    fun register(activationKey: String = "ABCD1234") {
        val issued = client.registerDevice(activationKey, DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
        certificate = DeviceCertificate.parse(issued.certificatePem).first()
        val config = client.getConfig()
        device = newDevice(config.toDeviceConfiguration(identity.deviceId))
    }

    fun newDevice(configuration: DeviceConfiguration) =
        FiscalDevice(configuration, signer, states, records, TestSealer(), FiscalClock { now })

    fun sync() = FiscalSync(device, client).run()
}
