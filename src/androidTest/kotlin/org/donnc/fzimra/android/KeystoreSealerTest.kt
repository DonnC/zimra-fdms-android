package org.donnc.fzimra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator: `./gradlew connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class KeystoreSealerTest {
    private val alias = "fzimra-test-seal-${System.nanoTime()}"
    private val other = "$alias-other"

    @After
    fun cleanUp() {
        KeystoreSealer.delete(alias)
        KeystoreSealer.delete(other)
    }

    @Test
    fun sealsAreDeterministicAndSurviveReloading() {
        val sealer = KeystoreSealer.getOrCreate(alias, requireHardware = false)
        val data = "fiscal state".toByteArray()
        assertEquals(32, sealer.seal(data).size)
        assertArrayEquals(sealer.seal(data), sealer.seal(data))
        assertArrayEquals(sealer.seal(data), KeystoreSealer.load(alias)!!.seal(data))
        assertArrayEquals(sealer.seal(data), KeystoreSealer.getOrCreate(alias, requireHardware = false).seal(data))
        assertFalse(sealer.seal(data).contentEquals(sealer.seal("fiscal state!".toByteArray())))
    }

    @Test
    fun eachKeySealsDifferently() {
        val data = "fiscal state".toByteArray()
        val a = KeystoreSealer.getOrCreate(alias, requireHardware = false).seal(data)
        val b = KeystoreSealer.getOrCreate(other, requireHardware = false).seal(data)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun aDeletedKeyIsGone() {
        KeystoreSealer.getOrCreate(alias, requireHardware = false)
        KeystoreSealer.delete(alias)
        assertNull(KeystoreSealer.load(alias))
        assertThrows(KeystoreUnavailableException::class.java) { KeystoreSealer.getOrCreate(" ") }
    }
}
