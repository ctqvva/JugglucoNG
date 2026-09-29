package tk.glucodata.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hard gate of plan §2.3 (docs/architecture/direction.md). Five checks whose direction is
 * never in doubt, each scanned directly from sources rather than through a shell script — a
 * broken grep in a bash gate reports "0, all clean" silently; a broken scan here just finds
 * fewer files, which [theScansActuallySeeSourceFiles] catches.
 *
 * Four of the five start from a nonzero count and use a shrink-only allow-list, the same
 * mechanism as [NoDuplicateFqnTest]: a new violation, or a stale line for one already fixed,
 * fails the build. The fifth (`fallbackToDestructiveMigration`) is already at zero and stays
 * there with no allow-list.
 *
 * Deliberately not here: duplicate mobile/wear FQNs (already guarded by [NoDuplicateFqnTest] —
 * counting it twice would mean two gates to update for one fix), and the broad per-file/per-line
 * counters the previous version of this gate used (`Applic.`/`Natives.` touched anywhere,
 * root-package line count, ...). Those move where every PR can see them without blocking one:
 * `scripts/arch-metrics.sh --print`, run in CI as a report step that never fails the build.
 */
class ArchitectureGateTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/MessageSender.kt").exists() }
            ?: working
    }

    private fun allowlist(name: String): Set<String> =
        File(moduleRoot, "src/test/resources/arch/$name").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    /** Files under `src/main`, `src/mobile`, `src/wear` that declare a `@Composable`. */
    private fun composableFiles(): List<File> =
        listOf("main", "mobile", "wear").flatMap { flavor ->
            File(moduleRoot, "src/$flavor/java").walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .filter { it.readText().contains("@Composable") }
                .toList()
        }

    // '/' on every OS: the allowlists are written that way, and a Windows checkout gives '\'.
    private fun relativePath(file: File): String = file.relativeTo(moduleRoot).invariantSeparatorsPath

    /**
     * A shrink-only gate: [violating] must be a subset of the allow-list, and the allow-list
     * must not name anything outside [violating] — a fixed pair left in the list would stop the
     * gate noticing if the same violation came back.
     */
    private fun assertShrinkOnly(violating: Set<String>, allowlistFile: String, describe: String) {
        val allowed = allowlist(allowlistFile)
        val new = (violating - allowed).sorted()
        val stale = (allowed - violating).sorted()
        assertTrue(
            "new $describe: $new. Either remove the cause, or add it to $allowlistFile with a reason.",
            new.isEmpty(),
        )
        assertTrue(
            "$allowlistFile lists $stale as $describe, but they no longer are. Remove them — the " +
                "list may only shrink, and a stale line hides the violation coming back.",
            stale.isEmpty(),
        )
    }

    @Test
    fun composablesDoNotGrowInCallsToNatives() {
        val violating = composableFiles().filter { it.readText().contains("Natives.") }
            .map(::relativePath).toSet()
        assertShrinkOnly(violating, "composable-natives-allowlist.txt", "Composables calling Natives.*")
    }

    @Test
    fun composablesDoNotGrowInCallsToApplic() {
        val violating = composableFiles().filter { it.readText().contains("Applic.") }
            .map(::relativePath).toSet()
        assertShrinkOnly(violating, "composable-applic-allowlist.txt", "Composables calling Applic.*")
    }

    @Test
    fun composablesDoNotGrowInDirectSharedPreferencesUse() {
        val violating = composableFiles().filter { it.readText().contains("SharedPreferences") }
            .map(::relativePath).toSet()
        assertShrinkOnly(
            violating,
            "composable-sharedprefs-allowlist.txt",
            "Composables touching SharedPreferences directly",
        )
    }

    /**
     * `Class.forName` from `src/main` into phone- or watch-only code (plan §6, Q1): each call
     * needs a ProGuard keep rule that only breaks in a minified build, and nothing in `src/main`
     * should know a variant class by name in the first place (plan P1).
     *
     * `Log.java`'s dynamic lookup is not a phone/watch bridge — it is not counted (direction.md
     * §2.3), so it is filtered out here rather than pinned in the allow-list forever.
     */
    @Test
    fun classForNameFromMainDoesNotGrow() {
        val violating = File(moduleRoot, "src/main/java").walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filterNot { it.name == "Log.java" }
            .filter { it.readText().contains("Class.forName") }
            .map(::relativePath).toSet()
        assertShrinkOnly(violating, "class-forname-allowlist.txt", "Class.forName from src/main")
    }

    /**
     * `fallbackToDestructiveMigration` on a Room database silently drops the user's history on a
     * migration Room cannot run, instead of failing loudly. It is at zero today and has no
     * allow-list: the direction here is not "shrink toward zero", it is "never introduced".
     */
    @Test
    fun fallbackToDestructiveMigrationIsNeverUsed() {
        val hits = listOf("main", "mobile", "wear").flatMap { flavor ->
            File(moduleRoot, "src/$flavor/java").walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .filter { it.readText().contains("fallbackToDestructiveMigration") }
                .map(::relativePath)
        }
        assertTrue(
            "fallbackToDestructiveMigration found in: $hits. A migration that cannot run must fail " +
                "loudly, not silently drop the user's history — write a real Migration instead.",
            hits.isEmpty(),
        )
    }

    @Test
    fun theScansActuallySeeSourceFiles() {
        // If the source-set paths stop matching, every test above would pass by finding nothing
        // to compare — the same failure mode this gate exists to catch in application code.
        assertTrue("no @Composable files found", composableFiles().size > 50)
        val mainFileCount = File(moduleRoot, "src/main/java").walkTopDown()
            .count { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
        assertTrue("no src/main sources found", mainFileCount > 100)
    }
}
