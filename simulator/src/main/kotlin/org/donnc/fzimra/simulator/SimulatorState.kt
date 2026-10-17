package org.donnc.fzimra.simulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException

/**
 * Where the simulator keeps its state between runs, as one piece of text.
 * Keep it alongside the device state it pairs with: a device whose state
 * survives while the simulator's does not would disagree with it.
 */
public interface SimulatorStore {
    /** The saved state, or `null` if there is none. */
    public fun load(): String?

    /** Replaces the saved state. */
    public fun save(text: String)

    /** Kept in memory only: a fresh gateway each time. */
    public class InMemory : SimulatorStore {
        @Volatile private var text: String? = null
        override fun load(): String? = text
        override fun save(text: String) {
            this.text = text
        }
    }

    /** Kept in [file], replaced atomically. */
    public class InFile(private val file: File) : SimulatorStore {
        override fun load(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

        override fun save(text: String) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(text, Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) throw IOException("cannot save the simulator state to $file")
            }
        }
    }
}

internal class SimReceipt(
    val body: JsonObject,
    val receiptId: Long,
    val findings: List<JsonObject>,
    val serverDate: String,
) {
    val globalNo: Int get() = body.int("receiptGlobalNo")
    val hash: String get() = body.obj("receiptDeviceSignature").text("hash")
    val date: String get() = body.text("receiptDate")
}

internal class SimDay(val number: Int, val opened: String) {
    val receipts = mutableListOf<SimReceipt>()

    /** Cents, keyed by type, currency, tax ID, percent ("15.00" or ""), money type. */
    val counters = linkedMapOf<CounterKey, Long>()
    var closed: String? = null
    var mode: String? = null
    var deviceSignature: String? = null
    var serverSignature: JsonObject? = null
}

internal data class CounterKey(val type: String, val currency: String, val taxId: Int?, val percent: String?, val moneyType: String?)

internal class SimDevice(val deviceId: Int, val serialNo: String) {
    var certificatePem: String? = null
    var status: String = FISCAL_DAY_CLOSED
    var closingErrorCode: String? = null
    val days = mutableListOf<SimDay>()
    var pendingClose: JsonObject? = null
    var closeRequestedAt: Long = 0

    val commonName: String get() = "ZIMRA-$serialNo-${deviceId.toString().padStart(10, '0')}"
}

internal const val FISCAL_DAY_CLOSED = "FiscalDayClosed"
internal const val FISCAL_DAY_OPENED = "FiscalDayOpened"
internal const val FISCAL_DAY_CLOSE_INITIATED = "FiscalDayCloseInitiated"
internal const val FISCAL_DAY_CLOSE_FAILED = "FiscalDayCloseFailed"

/** The simulator's whole state, to and from JSON. */
internal object SimulatorCodec {
    fun write(ca: SimulatorCa, nextReceiptId: Long, devices: Collection<SimDevice>): String = Json.encodeToString(
        JsonElement.serializer(),
        obj(
            "format" to JsonPrimitive(1),
            "caKey" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(ca.keys.private.encoded)),
            "caPublic" to JsonPrimitive(SimulatorCa.encode(ca.keys.public)),
            "caCertificate" to JsonPrimitive(ca.pem),
            "nextReceiptId" to JsonPrimitive(nextReceiptId),
            "devices" to JsonArray(devices.map(::device)),
        ),
    )

    class Restored(val ca: SimulatorCa, val nextReceiptId: Long, val devices: List<SimDevice>)

    fun read(text: String): Restored {
        val root = Json.parseToJsonElement(text).jsonObject
        val ca = SimulatorCa.restore(root.text("caKey"), root.text("caPublic"), root.text("caCertificate"))
        return Restored(ca, root.getValue("nextReceiptId").jsonPrimitive.long, root.getValue("devices").jsonArray.map { device(it.jsonObject) })
    }

    private fun device(d: SimDevice) = obj(
        "deviceId" to JsonPrimitive(d.deviceId),
        "serialNo" to JsonPrimitive(d.serialNo),
        "certificate" to (d.certificatePem?.let(::JsonPrimitive) ?: JsonNull),
        "status" to JsonPrimitive(d.status),
        "closingErrorCode" to (d.closingErrorCode?.let(::JsonPrimitive) ?: JsonNull),
        "pendingClose" to (d.pendingClose ?: JsonNull),
        "closeRequestedAt" to JsonPrimitive(d.closeRequestedAt),
        "days" to JsonArray(
            d.days.map { day ->
                obj(
                    "number" to JsonPrimitive(day.number),
                    "opened" to JsonPrimitive(day.opened),
                    "closed" to (day.closed?.let(::JsonPrimitive) ?: JsonNull),
                    "mode" to (day.mode?.let(::JsonPrimitive) ?: JsonNull),
                    "deviceSignature" to (day.deviceSignature?.let(::JsonPrimitive) ?: JsonNull),
                    "serverSignature" to (day.serverSignature ?: JsonNull),
                    "counters" to JsonArray(
                        day.counters.map { (k, v) ->
                            obj(
                                "type" to JsonPrimitive(k.type), "currency" to JsonPrimitive(k.currency),
                                "taxId" to (k.taxId?.let(::JsonPrimitive) ?: JsonNull),
                                "percent" to (k.percent?.let(::JsonPrimitive) ?: JsonNull),
                                "moneyType" to (k.moneyType?.let(::JsonPrimitive) ?: JsonNull),
                                "cents" to JsonPrimitive(v),
                            )
                        },
                    ),
                    "receipts" to JsonArray(
                        day.receipts.map {
                            obj(
                                "body" to it.body, "receiptId" to JsonPrimitive(it.receiptId),
                                "findings" to JsonArray(it.findings), "serverDate" to JsonPrimitive(it.serverDate),
                            )
                        },
                    ),
                )
            },
        ),
    )

    private fun device(o: JsonObject): SimDevice = SimDevice(o.int("deviceId"), o.text("serialNo")).apply {
        certificatePem = o.optText("certificate")
        status = o.text("status")
        closingErrorCode = o.optText("closingErrorCode")
        pendingClose = o["pendingClose"]?.takeIf { it !is JsonNull }?.jsonObject
        closeRequestedAt = o.getValue("closeRequestedAt").jsonPrimitive.long
        o.getValue("days").jsonArray.forEach { element ->
            val d = element.jsonObject
            days += SimDay(d.int("number"), d.text("opened")).apply {
                closed = d.optText("closed")
                mode = d.optText("mode")
                deviceSignature = d.optText("deviceSignature")
                serverSignature = d["serverSignature"]?.takeIf { it !is JsonNull }?.jsonObject
                d.getValue("counters").jsonArray.forEach {
                    val c = it.jsonObject
                    counters[CounterKey(c.text("type"), c.text("currency"), c.optInt("taxId"), c.optText("percent"), c.optText("moneyType"))] =
                        c.getValue("cents").jsonPrimitive.long
                }
                d.getValue("receipts").jsonArray.forEach {
                    val r = it.jsonObject
                    receipts += SimReceipt(
                        r.obj("body"), r.getValue("receiptId").jsonPrimitive.long,
                        r.getValue("findings").jsonArray.map { f -> f.jsonObject }, r.text("serverDate"),
                    )
                }
            }
        }
    }
}

internal fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*fields))

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.optText(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

internal fun JsonObject.optInt(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
