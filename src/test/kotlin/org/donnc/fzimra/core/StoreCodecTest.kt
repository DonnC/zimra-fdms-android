package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCodecTest {
    @Test
    fun snapshotRoundTripsExactly() {
        val snapshot = sampleSnapshot()
        assertEquals(snapshot, StoreCodec.decodeSnapshot(StoreCodec.encodeSnapshot(snapshot)))
    }

    @Test
    fun recordsRoundTripExactly() {
        for (status in ReceiptSyncStatus.entries) {
            val record = sampleRecord(5, status)
            assertEquals(record, StoreCodec.decodeRecord(StoreCodec.encodeRecord(record)))
        }
    }

    @Test
    fun amountsKeepTheirScale() {
        val decoded = StoreCodec.decodeSnapshot(StoreCodec.encodeSnapshot(sampleSnapshot()))
        val counter = decoded.state.currentDay!!.counters.first()
        assertEquals("2.30", counter.value.toPlainString())
        assertEquals("15", counter.taxPercent!!.toPlainString())
    }

    @Test
    fun amountsAreStoredAsText() {
        val json = StoreCodec.encodeSnapshot(sampleSnapshot())
        assertTrue(json.contains("\"value\":\"2.30\""))
    }

    @Test
    fun refusesAnotherFormat() {
        val json = StoreCodec.encodeSnapshot(sampleSnapshot()).replace("\"format\":1", "\"format\":2")
        assertThrows(FiscalStoreException::class.java) { StoreCodec.decodeSnapshot(json) }
    }

    @Test
    fun refusesIncompleteOrCorruptData() {
        val json = StoreCodec.encodeSnapshot(sampleSnapshot())
        assertThrows(FiscalStoreException::class.java) { StoreCodec.decodeSnapshot(json.replace("\"lastGlobalNo\":2,", "")) }
        assertThrows(FiscalStoreException::class.java) { StoreCodec.decodeSnapshot(json.replace("\"OPENED\"", "\"ON_HOLIDAY\"")) }
        assertThrows(FiscalStoreException::class.java) { StoreCodec.decodeSnapshot(json.dropLast(1)) }
        assertThrows(FiscalStoreException::class.java) { StoreCodec.decodeSnapshot("") }
    }
}

class JsonTest {
    @Test
    fun roundTripsValues() {
        val value = JsonValue.Obj(
            linkedMapOf(
                "text" to JsonValue.Str("quote \" backslash \\ newline \n tab \t bell \u0007 é ✓ 🇿🇼"),
                "list" to JsonValue.Arr(listOf(JsonValue.Num("-12.50e3"), JsonValue.Bool(true), JsonValue.Null)),
                "empty" to JsonValue.Obj(emptyMap()),
                "none" to JsonValue.Arr(emptyList()),
            ),
        )
        assertEquals(value, Json.parse(Json.write(value)))
    }

    @Test
    fun parsesWhitespaceAndEscapes() {
        assertEquals(
            JsonValue.Obj(mapOf("a" to JsonValue.Arr(listOf(JsonValue.Str("x/y\u00e9"))))),
            Json.parse(" { \"a\" : [ \"x\\/y\\u00e9\" ] } "),
        )
    }

    @Test
    fun refusesInvalidJson() {
        for (text in listOf("{", "[1,]", "{\"a\" 1}", "01", "1.", "\"open", "{} {}", "tru", "\"a\u0001\"", "\"\\x\"")) {
            assertThrows(text, FiscalStoreException::class.java) { Json.parse(text) }
        }
    }
}
