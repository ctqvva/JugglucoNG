package tk.glucodata.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards invariant I3 of the architecture plan: no class exists in both
 * `src/mobile` and `src/wear` with the same fully-qualified name.
 *
 * That pattern is how the watch silently loses features. `main/` compiles into
 * both apps and calls these classes statically; the two copies are unrelated at
 * compile time, so changing the phone one leaves the watch one behind. The
 * build stays green and the watch does nothing — the largest observed class of
 * regression. T5.1 replaces the pairs with shared contracts; until then the
 * allow-list below may only shrink.
 */
class NoDuplicateFqnTest {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    private val allowlistFile = File(moduleRoot, "src/test/resources/arch/duplicate-fqns-allowlist.txt")

    private fun fqns(flavor: String): Set<String> =
        File(moduleRoot, "src/$flavor/java").walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .mapNotNull { file ->
                val pkg = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(file.readText())?.groupValues?.get(1) ?: return@mapNotNull null
                "$pkg.${file.nameWithoutExtension}"
            }
            .toSet()

    private fun allowlist(): Set<String> =
        allowlistFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    @Test
    fun noNewDuplicateFqnAcrossMobileAndWear() {
        val added = ((fqns("mobile") intersect fqns("wear")) - allowlist()).sorted()
        assertTrue(
            "new classes with the same FQN in src/mobile and src/wear: $added. " +
                "The watch copy can silently diverge from the phone one. Give the pair a shared " +
                "contract with one implementation per flavour, or list it in " +
                "${allowlistFile.name} with a reason.",
            added.isEmpty(),
        )
    }

    @Test
    fun theAllowlistStillListsRealDuplicates() {
        val stale = (allowlist() - (fqns("mobile") intersect fqns("wear"))).sorted()
        assertTrue(
            "listed as duplicate FQNs but no longer are: $stale. Remove them — the list may " +
                "only shrink, and a stale line hides the pair coming back.",
            stale.isEmpty(),
        )
    }

    @Test
    fun theScanActuallySeesTheSourceSets() {
        // If the paths or the package regex stop matching, the two tests above
        // would pass by finding nothing to compare.
        assertTrue("no mobile classes parsed", fqns("mobile").size > 50)
        assertTrue("no wear classes parsed", fqns("wear").size > 50)
    }
}
