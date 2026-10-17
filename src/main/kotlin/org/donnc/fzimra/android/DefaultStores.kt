package org.donnc.fzimra.android

import android.content.Context
import android.util.AtomicFile
import org.donnc.fzimra.core.FiscalStateStore
import org.donnc.fzimra.core.FiscalStoreException
import org.donnc.fzimra.core.ReceiptRecordStore
import org.donnc.fzimra.core.TextRecordStore
import org.donnc.fzimra.core.TextStateStore
import org.donnc.fzimra.core.TerminalStore
import org.donnc.fzimra.core.TextStorage
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * The stores fzimra uses when the app provides none. Private to the app and
 * managed by fzimra; the app only ever reads records through
 * [org.donnc.fzimra.core.ReceiptRecords].
 *
 * Both live in the app's no-backup storage. Restoring an old copy of the
 * device state from a backup would make the device reuse receipt numbers
 * ZIMRA has already received, so fiscal data is never backed up.
 */
internal object DefaultStores {
    private const val ROOT = "org.donnc.fzimra"

    /**
     * Each gateway a terminal uses keeps its own files ([namespace]), so a
     * simulated device never shares state with a ZIMRA one. `null` is the
     * layout of `Fzimra.device`.
     */
    private fun directory(context: Context, namespace: String?, kind: String) =
        File(context.noBackupFilesDir, if (namespace == null) "$ROOT/$kind" else "$ROOT/$namespace/$kind")

    /** Device state: one small file per device, replaced atomically, never pruned. */
    fun state(context: Context, namespace: String? = null): FiscalStateStore =
        TextStateStore(FileStorage(directory(context, namespace, "state")))

    /**
     * Receipt records as one file each, so that saving a record does not
     * rewrite the others. Accepted receipts are kept for [retention].
     */
    fun records(context: Context, namespace: String? = null, retention: Duration = TextRecordStore.DEFAULT_RETENTION): ReceiptRecordStore =
        TextRecordStore(FileStorage(directory(context, namespace, "records")), retention)

    /** The terminal's own small state: configuration and invoice numbers. */
    fun terminal(context: Context, namespace: String, deviceId: Int): TerminalStore = object : TerminalStore {
        private val storage = FileStorage(directory(context, namespace, "terminal"))
        private val key = "terminal.$deviceId"
        override fun load(): String? = storage.read(key)
        override fun save(text: String) = storage.write(mapOf(key to text))
        override fun delete() = storage.write(mapOf(key to null))
    }

    /** Unsent receipts kept for [deviceId], read without the seal check. */
    fun unsentReceipts(context: Context, deviceId: Int, namespace: String? = null): Int =
        TextStateStore(FileStorage(directory(context, namespace, "state"))).read(deviceId)?.outbox?.size ?: 0

    fun delete(context: Context, deviceId: Int, namespace: String? = null) {
        TextStateStore(FileStorage(directory(context, namespace, "state"))).delete(deviceId)
        TextRecordStore(FileStorage(directory(context, namespace, "records"))).deleteAll(deviceId)
    }
}

/**
 * [TextStorage] with one file per key in [directory]. Each file is replaced
 * atomically ([AtomicFile]). Keys are fzimra's own and contain only letters,
 * digits and dots.
 */
internal class FileStorage(private val directory: File) : TextStorage {
    private val validKey = Regex("^[A-Za-z0-9.]+$")

    override fun read(key: String): String? {
        val file = file(key)
        if (!file.baseFile.exists()) return null
        return try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw FiscalStoreException("could not read '$key'", e)
        }
    }

    override fun keys(): Set<String> = directory.list()?.filter { validKey.matches(it) }?.toSet().orEmpty()

    override fun write(changes: Map<String, String?>) {
        if (!directory.isDirectory && !directory.mkdirs()) throw FiscalStoreException("could not create $directory")
        for ((key, value) in changes) {
            val file = file(key)
            if (value == null) {
                file.delete()
                continue
            }
            val stream = try {
                file.startWrite()
            } catch (e: IOException) {
                throw FiscalStoreException("could not write '$key'", e)
            }
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream) // flushes to disk and replaces the file
            } catch (e: IOException) {
                file.failWrite(stream)
                throw FiscalStoreException("could not write '$key'", e)
            }
        }
    }

    private fun file(key: String): AtomicFile {
        if (!validKey.matches(key)) throw FiscalStoreException("invalid storage key '$key'")
        return AtomicFile(File(directory, key))
    }
}
