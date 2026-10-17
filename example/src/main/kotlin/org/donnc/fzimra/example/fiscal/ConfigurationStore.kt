package org.donnc.fzimra.example.fiscal

import android.content.Context
import org.donnc.fzimra.core.Address
import org.donnc.fzimra.core.BranchDetails
import org.donnc.fzimra.core.Contacts
import org.donnc.fzimra.core.OperatingMode
import org.donnc.fzimra.core.TaxDefinition
import org.donnc.fzimra.core.ZimraConfiguration
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Keeps ZIMRA's configuration for each device, so the app can issue receipts
 * after a restart without first reaching ZIMRA. Refreshed whenever a fiscal
 * day opens.
 */
class ConfigurationStore(context: Context) {
    private val directory = File(context.filesDir, "zimra-configuration")

    fun load(key: String): ZimraConfiguration? = file(key).takeIf { it.exists() }?.let {
        runCatching { decode(JSONObject(it.readText())) }.getOrNull()
    }

    fun save(key: String, configuration: ZimraConfiguration) {
        directory.mkdirs()
        val temporary = File(directory, "$key.tmp")
        temporary.writeText(encode(configuration).toString())
        temporary.renameTo(file(key))
    }

    fun delete(key: String) {
        file(key).delete()
    }

    private fun file(key: String) = File(directory, "$key.json")

    private fun encode(c: ZimraConfiguration) = JSONObject()
        .put("taxpayerName", c.taxpayerName)
        .put("taxpayerTin", c.taxpayerTin)
        .put("vatNumber", c.vatNumber ?: JSONObject.NULL)
        .put("serialNo", c.serialNo ?: JSONObject.NULL)
        .put("branchName", c.branch.name)
        .put("province", c.branch.address?.province ?: JSONObject.NULL)
        .put("city", c.branch.address?.city ?: JSONObject.NULL)
        .put("street", c.branch.address?.street ?: JSONObject.NULL)
        .put("houseNo", c.branch.address?.houseNo ?: JSONObject.NULL)
        .put("phoneNo", c.branch.contacts?.phoneNo ?: JSONObject.NULL)
        .put("email", c.branch.contacts?.email ?: JSONObject.NULL)
        .put("operatingMode", c.operatingMode.name)
        .put("dayMaxHours", c.dayMaxHours)
        .put("dayEndNotificationHours", c.dayEndNotificationHours)
        .put("certificateValidTill", c.certificateValidTill?.toString() ?: JSONObject.NULL)
        .put("qrUrl", c.qrUrl)
        .put(
            "taxes",
            JSONArray().apply {
                c.taxes.forEach { t ->
                    put(
                        JSONObject().put("id", t.taxId).put("name", t.name)
                            .put("percent", t.percent?.toPlainString() ?: JSONObject.NULL)
                            .put("validFrom", t.validFrom?.toString() ?: JSONObject.NULL)
                            .put("validTill", t.validTill?.toString() ?: JSONObject.NULL),
                    )
                }
            },
        )

    private fun decode(o: JSONObject) = ZimraConfiguration(
        operationId = null,
        taxpayerName = o.getString("taxpayerName"),
        taxpayerTin = o.getString("taxpayerTin"),
        vatNumber = o.text("vatNumber"),
        serialNo = o.text("serialNo"),
        branch = BranchDetails(
            o.getString("branchName"),
            Address(o.text("province"), o.text("city"), o.text("street"), o.text("houseNo")),
            Contacts(o.text("phoneNo"), o.text("email")),
        ),
        operatingMode = OperatingMode.valueOf(o.getString("operatingMode")),
        dayMaxHours = o.getInt("dayMaxHours"),
        dayEndNotificationHours = o.getInt("dayEndNotificationHours"),
        taxes = o.getJSONArray("taxes").let { array ->
            (0 until array.length()).map { i ->
                val t = array.getJSONObject(i)
                TaxDefinition(
                    t.getInt("id"), t.getString("name"), t.text("percent")?.let(::BigDecimal),
                    t.text("validFrom")?.let(LocalDate::parse), t.text("validTill")?.let(LocalDate::parse),
                )
            }
        },
        certificateValidTill = o.text("certificateValidTill")?.let(LocalDate::parse),
        qrUrl = o.getString("qrUrl"),
    )

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
}
