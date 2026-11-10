package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON the Nightscout writers are built by string concatenation, checked as a sequence of
 * fragments in the source.
 *
 * This is the weak kind of test and it is here because of a specific bug: moving the identifier's
 * closing `"` out of the if/else in `writev3entry` left the `server` branch opening with
 * `","created_at":"`, so every v3 `/pebble` response and `nightexport` was malformed. It compiled,
 * and nothing ran it. There is no C++ test target in this tree and Gradle never configures one
 * (`CMakeLists.txt` builds the host `juggluco` binary only in the `JUGGLUCO_APP`-false branch), so a
 * real test means new CMake *and* Gradle wiring to build and exec a host binary.
 *
 * What this catches instead is the class that does not need arithmetic: **a fragment that continues
 * an object and does not begin with a comma**, and a closing quote that moved away from the value it
 * closes. Both are order-in-source facts, which is all a text check can honestly claim.
 *
 * Two things it does not do: it does not run the writers, and it does not check the values. A
 * legitimate reordering of these writers turns it red, and that is the cost — the fix is to move the
 * anchor, never to weaken the assertion.
 */
class NightscoutPayloadWriterShapeTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/cpp/net/watchserver/common.cpp").exists() }
            ?: working
    }

    private fun common() = File(moduleRoot, "src/main/cpp/net/watchserver/common.cpp").readText()
    private fun uploader() = File(moduleRoot, "src/main/cpp/net/watchserver/uploader.cpp").readText()

    /**
     * The `addar(out…, R"(…)")` literals of one function, in the order the source writes them.
     *
     * [endIndent] is how the file closes a function: `common.cpp` uses a tab, `uploader.cpp` four
     * spaces. Brace counting would not do — the literals are full of braces (`R"("})"`).
     */
    private fun fragments(text: String, function: String, endIndent: String): List<String> {
        val start = text.indexOf(function)
        assertTrue("$function is not in the file any more, so this test is vacuous", start >= 0)
        val body = text.substring(start)
        val end = Regex("(?m)^${Regex.escape(endIndent)}\\}$").find(body)?.range?.first ?: -1
        assertTrue(
            "could not find the end of $function -- the file's brace style changed, so this test " +
                "would read the next function's fragments",
            end > 0,
        )
        return Regex("""addar\(out\w*,\s*R"\((.*?)\)"\)""")
            .findAll(body.substring(0, end))
            .map { it.groupValues[1] }
            .toList()
    }

    /** Fragments that legitimately begin with a quote: the ones that close something. */
    private val closers = setOf("\"", "\"}", "\"},", "\"}}")

    /** The literal that opens the identifier, as the writer spells it. */
    private val IDENTIFIER = ",\"identifier\":\""

    @Test
    fun theV3ResponseClosesTheIdentifierBeforeItOpensTheNextField() {
        val written = fragments(common(), "char * writev3entry(", "\t")
        val identifierAt = written.indexOfFirst { it == IDENTIFIER }
        assertTrue("the writer no longer opens the identifier, so this anchor moved", identifierAt >= 0)

        val closingAt = written.indexOfFirst { it == "\"" }
        assertEquals(
            "exactly one fragment closes a value: two would mean one of them closes nothing, and " +
                "none would leave the identifier open",
            1,
            written.count { it == "\"" },
        )
        assertTrue(
            "the closing quote is written after the identifier is opened",
            closingAt > identifierAt,
        )

        val createdAtAt = written.indexOfFirst { it.contains("created_at") }
        assertTrue("the server branch no longer writes created_at", createdAtAt > 0)
        assertTrue(
            "the field after the identifier has to start with a comma, or the object is malformed: " +
                "this fragment starts with a quote",
            written[createdAtAt].startsWith(","),
        )
    }

    @Test
    fun aFragmentMayOnlyOpenWithAQuoteWhileAValueIsStillOpen() {
        val written = fragments(common(), "char * writev3entry(", "\t")
        assertTrue("no fragments found: the regex moved", written.isNotEmpty())

        // Two legal shapes, and one that was shipped broken:
        //  - `{"app":"Juggluco","device":"` leaves the device name open, so the next fragment
        //    starting with `","date":` is correct;
        //  - a value written raw and then closed by a `"` fragment, after which the next fragment
        //    has to start with a comma. That is the bug: the identifier's closing quote moved out of
        //    the branch, and the `server` branch still opened `","created_at":"`.
        // So the state is "is a value open", not "does the fragment start with a comma" -- the
        // writer splits numbers and names across fragments, which is why the obvious rule was wrong.
        // The first fragment is the one that opens the document, so it is not checked -- but it does
        // set the state, because it leaves the device name open.
        var valueOpen = written.first().trimEnd('"').endsWith(":")
        val offenders = mutableListOf<String>()
        written.drop(1).forEach { fragment ->
            if (fragment.startsWith("\"") && !valueOpen && fragment !in closers) {
                offenders += fragment
            }
            // A fragment ending in `:` or `:"` opens a value that the next fragment writes raw --
            // that is how every number and every sensor name in this writer gets in. Ending in a
            // bare `"` closes one.
            valueOpen = fragment.trimEnd('"').endsWith(":")
        }
        assertEquals(
            "a fragment that opens with a quote while no value is open: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theV1WriterClosesItsIdentifierToo() {
        val written = fragments(uploader(), "int mkuploaditem(", "    ")
        val identifierAt = written.indexOfFirst { it == IDENTIFIER }
        assertTrue(
            "the v1 writer no longer opens the identifier, so this anchor moved",
            identifierAt >= 0,
        )
        val closingAt = written.indexOfFirst { it == "\"" }
        assertTrue("the v1 writer no longer closes the identifier", closingAt > identifierAt)
        assertTrue(
            "and it still writes the fields it always did",
            written.any { it.contains("\"type\":\"sgv\"") } &&
                written.any { it.contains("\"dateString\"") } &&
                written.any { it.contains("\"rssi\"") },
        )
    }
}
