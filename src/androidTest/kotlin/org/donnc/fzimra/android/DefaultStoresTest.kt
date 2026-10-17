package org.donnc.fzimra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.donnc.fzimra.core.DeviceState
import org.donnc.fzimra.core.FiscalSnapshot
import org.donnc.fzimra.core.FiscalStoreException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on a device or emulator: `./gradlew connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class DefaultStoresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "fzimra-test-" + System.nanoTime())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun fileStorageWritesReadsAndRemoves() {
        val storage = FileStorage(directory)
        storage.write(mapOf("fzimra.a" to "one", "fzimra.b" to "two"))
        assertEquals("one", storage.read("fzimra.a"))
        assertEquals(setOf("fzimra.a", "fzimra.b"), storage.keys())
        storage.write(mapOf("fzimra.a" to "uno", "fzimra.b" to null))
        assertEquals("uno", storage.read("fzimra.a"))
        assertNull(storage.read("fzimra.b"))
    }

    @Test
    fun fileStoragePersistsAcrossInstances() {
        FileStorage(directory).write(mapOf("fzimra.state.1" to "{\"x\":1}"))
        assertEquals("{\"x\":1}", FileStorage(directory).read("fzimra.state.1"))
    }

    @Test
    fun fileStorageRefusesUnsafeKeys() {
        assertThrows(FiscalStoreException::class.java) { FileStorage(directory).write(mapOf("../escape" to "x")) }
    }

    @Test
    fun defaultStateStoreKeepsTheSnapshotAcrossInstances() {
        val deviceId = (System.nanoTime() % 1_000_000).toInt() + 1
        val snapshot = FiscalSnapshot(1, DeviceState(deviceId, 0, null, 0, null), emptyList())
        try {
            DefaultStores.state(context).write(snapshot)
            assertEquals(snapshot, DefaultStores.state(context).read(deviceId))
        } finally {
            File(context.noBackupFilesDir, "org.donnc.fzimra/state/fzimra.state.$deviceId").delete()
        }
    }

    @Test
    fun fiscalDataLivesInNoBackupStorage() {
        val deviceId = (System.nanoTime() % 1_000_000).toInt() + 1
        val file = File(context.noBackupFilesDir, "org.donnc.fzimra/state/fzimra.state.$deviceId")
        try {
            DefaultStores.state(context).write(FiscalSnapshot(1, DeviceState(deviceId, 0, null, 0, null), emptyList()))
            assertTrue("state must be in noBackupFilesDir", file.exists())
        } finally {
            file.delete()
        }
    }
}
