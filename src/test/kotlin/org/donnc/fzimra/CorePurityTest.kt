package org.donnc.fzimra

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fiscal kernel (`core`) must stay plain Kotlin: no Android imports. This
 * keeps it testable on the JVM and reusable outside Android, without
 * splitting the library into modules.
 */
class CorePurityTest {
    @Test
    fun coreHasNoAndroidImports() {
        val core = File("src/main/kotlin/org/donnc/fzimra/core")
        assertTrue("core package missing: ${core.absolutePath}", core.isDirectory)
        val offenders = core.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readLines().any { it.trimStart().startsWith("import android") } }
            .map { it.relativeTo(core).path }
            .toList()
        assertTrue("Android imports in core: $offenders", offenders.isEmpty())
    }
}
