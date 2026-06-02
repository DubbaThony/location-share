package pl.dubba.share

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parity check between the default (English) `strings.xml` and the Polish
 * one. Runs as a plain JVM unit test - no Android runtime needed - by
 * reading the resource files straight off disk through the standard
 * `javax.xml` parser.
 *
 * Two assertions:
 *   1. Every key present in English exists in Polish (no missing translations).
 *   2. Polish doesn't have any keys English doesn't (no orphaned strings).
 *
 * On failure the diff is printed so adding the missing keys to `values-pl/`
 * is mechanical. Add a new English string → this test goes red until you
 * either translate it or copy it through as-is for keys that are universal
 * (URLs, brand names, etc.).
 */
class TranslationCoverageTest {

    private val baseDir = File("src/main/res").also {
        check(it.exists()) {
            "expected to run from app/ module dir; res not found at ${it.absolutePath}"
        }
    }
    private val enFile = File(baseDir, "values/strings.xml")
    private val plFile = File(baseDir, "values-pl/strings.xml")

    private fun keysOf(xml: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val strings = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until strings.length) {
            val name = (strings.item(i) as Element).getAttribute("name")
            if (name.isNotBlank()) keys += name
        }
        return keys
    }

    @Test
    fun englishHasPolishCoverage() {
        val en = keysOf(enFile)
        val pl = keysOf(plFile)

        val missingInPl = (en - pl).sorted()
        val extraInPl = (pl - en).sorted()

        if (missingInPl.isNotEmpty() || extraInPl.isNotEmpty()) {
            val msg = buildString {
                if (missingInPl.isNotEmpty()) {
                    appendLine("[MISSING IN values-pl/] ${missingInPl.size} key(s):")
                    missingInPl.forEach { appendLine("  - $it") }
                }
                if (extraInPl.isNotEmpty()) {
                    if (missingInPl.isNotEmpty()) appendLine()
                    appendLine("[ORPHANED IN values-pl/] ${extraInPl.size} key(s) not present in English:")
                    extraInPl.forEach { appendLine("  - $it") }
                }
            }
            fail("Translation parity broken between values/ and values-pl/.\n\n$msg")
        }

        // Quiet sanity assertion so a future "skip on empty" refactor doesn't
        // silently turn this into a no-op test.
        assertTrue(en.isNotEmpty(), "values/strings.xml has no <string> entries - something is wrong with the test setup")
    }
}
