package org.donnc.fzimra.core

import java.math.BigDecimal
import java.time.LocalDate

/** What a terminal keeps besides fiscal state: ZIMRA's configuration and its own invoice numbers. */
internal data class TerminalData(
    val configuration: ZimraConfiguration? = null,
    val nextInvoiceNo: Long = 1,
    val nextCreditNoteNo: Long = 1,
)

internal object TerminalCodec {
    fun encode(d: TerminalData): String = Json.write(
        obj(
            "format" to JsonValue.Num("1"),
            "nextInvoiceNo" to JsonValue.Num(d.nextInvoiceNo.toString()),
            "nextCreditNoteNo" to JsonValue.Num(d.nextCreditNoteNo.toString()),
            "configuration" to d.configuration?.let(::configuration),
        ),
    )

    fun decode(text: String): TerminalData {
        val o = Json.parse(text) as JsonValue.Obj
        return TerminalData(
            configuration = (o.fields["configuration"] as? JsonValue.Obj)?.let(::configuration),
            nextInvoiceNo = (o.fields["nextInvoiceNo"] as JsonValue.Num).text.toLong(),
            nextCreditNoteNo = (o.fields["nextCreditNoteNo"] as JsonValue.Num).text.toLong(),
        )
    }

    private fun configuration(c: ZimraConfiguration) = obj(
        "taxpayerName" to str(c.taxpayerName),
        "taxpayerTin" to str(c.taxpayerTin),
        "vatNumber" to c.vatNumber?.let(::str),
        "serialNo" to c.serialNo?.let(::str),
        "branchName" to str(c.branch.name),
        "address" to c.branch.address?.let {
            obj("province" to it.province?.let(::str), "city" to it.city?.let(::str), "street" to it.street?.let(::str), "houseNo" to it.houseNo?.let(::str), "district" to it.district?.let(::str))
        },
        "contacts" to c.branch.contacts?.let { obj("phoneNo" to it.phoneNo?.let(::str), "email" to it.email?.let(::str)) },
        "operatingMode" to str(c.operatingMode.name),
        "dayMaxHours" to JsonValue.Num(c.dayMaxHours.toString()),
        "dayEndNotificationHours" to JsonValue.Num(c.dayEndNotificationHours.toString()),
        "certificateValidTill" to c.certificateValidTill?.let { str(it.toString()) },
        "qrUrl" to str(c.qrUrl),
        "taxes" to JsonValue.Arr(
            c.taxes.map { t ->
                obj(
                    "taxId" to JsonValue.Num(t.taxId.toString()), "name" to str(t.name),
                    "percent" to t.percent?.let { str(it.toPlainString()) },
                    "validFrom" to t.validFrom?.let { str(it.toString()) }, "validTill" to t.validTill?.let { str(it.toString()) },
                )
            },
        ),
    )

    private fun configuration(o: JsonValue.Obj): ZimraConfiguration {
        val address = o.fields["address"] as? JsonValue.Obj
        val contacts = o.fields["contacts"] as? JsonValue.Obj
        return ZimraConfiguration(
            operationId = null,
            taxpayerName = o.text("taxpayerName"),
            taxpayerTin = o.text("taxpayerTin"),
            vatNumber = o.optText("vatNumber"),
            serialNo = o.optText("serialNo"),
            branch = BranchDetails(
                o.text("branchName"),
                address?.let { Address(it.optText("province"), it.optText("city"), it.optText("street"), it.optText("houseNo"), it.optText("district")) },
                contacts?.let { Contacts(it.optText("phoneNo"), it.optText("email")) },
            ),
            operatingMode = OperatingMode.valueOf(o.text("operatingMode")),
            dayMaxHours = (o.fields["dayMaxHours"] as JsonValue.Num).text.toInt(),
            dayEndNotificationHours = (o.fields["dayEndNotificationHours"] as JsonValue.Num).text.toInt(),
            taxes = (o.fields["taxes"] as JsonValue.Arr).items.map {
                val t = it as JsonValue.Obj
                TaxDefinition(
                    (t.fields["taxId"] as JsonValue.Num).text.toInt(), t.text("name"), t.optText("percent")?.let(::BigDecimal),
                    t.optText("validFrom")?.let(LocalDate::parse), t.optText("validTill")?.let(LocalDate::parse),
                )
            },
            certificateValidTill = o.optText("certificateValidTill")?.let(LocalDate::parse),
            qrUrl = o.text("qrUrl"),
        )
    }

    private fun obj(vararg fields: Pair<String, JsonValue?>) =
        JsonValue.Obj(LinkedHashMap<String, JsonValue>().apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } })

    private fun str(value: String) = JsonValue.Str(value)
    private fun JsonValue.Obj.text(key: String) = (fields[key] as JsonValue.Str).value
    private fun JsonValue.Obj.optText(key: String) = (fields[key] as? JsonValue.Str)?.value
}
