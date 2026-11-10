package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Specific` pair, after the seam, and the two things about it that needed a decision
 * rather than a recipe.
 *
 * **`tk.glucodata.Specific` is still on both flavours, on purpose.** The §4 recipe says to
 * rename a duplicate pair per variant; here that is impossible without paying somewhere
 * worse. `src/main` cannot name a class in `src/mobile` or `src/wear`, so the two same-named
 * classes are the only handle shared code has on the flavour. The alternative -- a per-variant
 * `Application` subclass -- needs `android:name` repointed in two flavour manifests plus
 * `tools:replace` against the main manifest, a mistake that only shows at runtime, and it moves
 * the registration out of `Applic.onCreate()`, which is the ordering that closed the
 * post-reboot window where forecast alerts fired on the two-point fallback slope. Same reason
 * `ui.AlarmActivity` is an allow-list entry. So each `Specific` is now a shim with exactly one
 * member, and [theShimDeclaresOnlyRegisterBridges] is what stops it growing back.
 *
 * **`useclose` was never variant state.** It looked like it: a `static final true` on the phone,
 * a mutable `false` on the watch written by `setclose`. It is a cache of a persisted native
 * setting. `Settings` writes the setting and restarts the activity, and `initbroadcasts()` made
 * a second copy at startup, so the cache had two writers and no owner. The contract now asks
 * the question directly -- `useCloseButton()`, the phone `true`, the watch
 * `!Natives.getdontuseclose()` -- and both the cache and its writer are gone.
 *
 * Source checks, as in GlucoseAlarmsAsymmetryTests, and every assertion quotes an anchor it
 * checks first. That is not ceremony: GlucoseAlarmsAsymmetryTests went stale under #469, and
 * this file was wrong four separate times in one session, every time by reading a partial
 * listing and reconstructing the rest from memory.
 */
class SpecificCompositionRootTests {
    private val phone: String = read("Common/src/mobile/java/tk/glucodata/Specific.java")
    private val watch: String = read("Common/src/wear/java/tk/glucodata/Specific.java")
    private val phoneBootstrap: String =
        read("Common/src/${sourceSet("phone")}/java/tk/glucodata/${bootstrapClass("phone")}.java")
    private val watchBootstrap: String =
        read("Common/src/wear/java/tk/glucodata/WearVariantBootstrap.java")
    private val contract: String = read("Common/src/main/java/tk/glucodata/VariantBootstrap.kt")
    private val applic: String = read("Common/src/main/java/tk/glucodata/Applic.java")
    private val mainActivity: String = read("Common/src/main/java/tk/glucodata/MainActivity.java")
    private val settings: String = read("Common/src/main/java/tk/glucodata/settings/Settings.java")

    /**
     * The ruling, and the reason it needs a test at all.
     *
     * `BridgeRegistrationTest` covers what the shim *registers*, not what the class *declares*.
     * A member added back to either flavour would therefore be a per-variant difference with no
     * test noticing, and the codebase would have a second class of that shape again. This is
     * the only thing standing between the shim and becoming a bootstrap that drifts.
     */
    @Test
    fun theShimDeclaresOnlyRegisterBridges() {
        for ((flavour, source) in listOf("phone" to phone, "watch" to watch)) {
            assertEquals(
                "Specific on the $flavour is a shim, not a bootstrap: it must declare " +
                    "exactly one member, registerBridges(). Anything else here is a " +
                    "per-variant difference that no test would catch, and the allow-list " +
                    "entry for it only says the class exists, not what is in it.",
                listOf("registerBridges"),
                declaredMembers(source, "Specific", flavour),
            )
        }
    }

    /**
     * The eight members the recipe expects to find are on the contract, not on the shims, and
     * both flavours implement all of them. Compared by name so that a member added to one side
     * only fails here rather than at some call site.
     */
    @Test
    fun theOtherEightMembersMovedBehindVariantBootstrap() {
        val expected = listOf(
            "start", "splash", "initScreen", "wearnosensors",
            "historyDatabaseCompatible", "settext", "rmlayout", "useCloseButton",
        )
        for ((flavour, source) in
            listOf("phone" to phoneBootstrap, "watch" to watchBootstrap)) {
            assertEquals(
                "the $flavour bootstrap must implement the whole contract; a one-sided " +
                    "member is a flavour difference with no caller to catch it",
                expected,
                declaredMembers(source, bootstrapClass(flavour), flavour).filter { it in expected },
            )
        }
        for (member in expected) {
            assertTrue(
                "the contract is missing $member; the shared call sites still expect it",
                contract.contains("fun $member("),
            )
        }
    }

    /**
     * The one divergence that was behavioural rather than cosmetic. `settext` returned `void`
     * on the phone and `boolean` on the watch, where the value only said whether the startup
     * text view existed to put the message in. Nothing read it -- not in any compiled source
     * set, not in native code -- so both sides are `void` now and the watch drops it.
     */
    @Test
    fun bothFlavoursNowAgreeOnSettext() {
        for ((flavour, source) in
            listOf("phone" to phoneBootstrap, "watch" to watchBootstrap)) {
            mustContain(source, "void settext(String text)", "$flavour bootstrap")
            assertFalse(
                "settext returning a value on the $flavour is the disagreement this seam " +
                    "removed; the value was never read",
                source.contains("boolean settext"),
            )
        }
        assertFalse(
            "the old phone signature was void settext(String str); if this matches, the " +
                "anchor moved and the assertions above are checking the wrong thing",
            phone.contains("void settext(String str)"),
        )
    }

    /**
     * The remaining divergences were parameter types only, and every shared call site already
     * passed the narrow type, so the contract keeps it: no `Any`, no `Object` widening. Checked
     * against the callers rather than assumed, because "the caller passes a MainActivity" is
     * what makes the narrow type correct.
     */
    @Test
    fun theContractUsesTheNarrowTypesTheCallersAlreadyPass() {
        for (member in listOf("splash", "initScreen", "wearnosensors")) {
            mustContain(contract, "fun $member(activity: MainActivity)", "contract")
            for ((flavour, source) in
                listOf("phone" to phoneBootstrap, "watch" to watchBootstrap)) {
                mustContain(source, "$member(MainActivity activity)", "$flavour bootstrap")
            }
        }
        mustContain(contract, "fun start(application: Application)", "contract")
        mustContain(mainActivity, "VariantBootstrapAccess.create().initScreen(this)", "MainActivity")
        mustContain(mainActivity, "VariantBootstrapAccess.create().splash(this)", "MainActivity")
        mustContain(applic, "VariantBootstrapAccess.create().start(this)", "Applic")
    }

    /**
     * The cache and both of its writers are gone, and the watch answers from the setting.
     *
     * The polarity is easy to get backwards: the checkbox and the setting are opposites, which
     * is why `Settings` writes `!isChecked` and the watch returns `!getdontuseclose()`. That
     * inversion is the whole reason the two lines are asserted together.
     */
    @Test
    fun theUsecloseCacheAndBothWritersAreGone() {
        for ((flavour, source) in listOf("phone" to phone, "watch" to watch)) {
            assertFalse(
                "the $flavour shim must not hold a useclose field; it is a cache of a " +
                    "persisted native setting, not variant state",
                Regex("""\buseclose\b""").containsMatchIn(source),
            )
            assertFalse(
                "setclose had two writers and no owner, and both are gone; a surviving " +
                    "$flavour setclose would re-introduce the cache",
                source.contains("setclose"),
            )
        }
        mustContain(watchBootstrap, "return !Natives.getdontuseclose();", "watch bootstrap")
        mustContain(phoneBootstrap, "return true;", "phone bootstrap")

        // The one writer that remains is the one that owns the setting.
        mustContain(settings, "Natives.setdontuseclose(!isChecked);", "Settings")
        assertFalse(
            "initbroadcasts() copied the cache at startup; with the cache gone the line has " +
                "nothing to seed",
            applic.contains("setclose"),
        )
    }

    /**
     * All 28 reads go through one static helper, so the static imports stay one-liners.
     *
     * Scans the tree rather than a list of files, so a new reader cannot be added with the old
     * field and slip past. The old field is gone, so any file still naming it would not compile
     * -- the scan is here to make the failure legible rather than to catch a live bug.
     */
    @Test
    fun everyReaderGoesThroughTheSharedHelper() {
        // R.string.useclose is the checkbox's resource name and stays; the field is gone.
        val stale = filesMatching(Regex("""\buseclose\b"""), skipResourcesAndComments = true)
        assertEquals(
            "no source file may still reference the removed useclose field: $stale",
            emptyList<String>(),
            stale,
        )
        val readers = filesMatching(Regex("""\buseCloseButton\(\)"""))
        assertTrue(
            "expected the readers to have moved to the shared helper, found $readers",
            readers.size >= 10,
        )
        mustContain(
            read("Common/src/main/java/tk/glucodata/VariantBootstrap.kt"),
            "fun useCloseButton(): Boolean = create().useCloseButton()",
            "the shared helper",
        )
    }

    /**
     * Unchanged by this seam, and still worth pinning: shared code calls it once, in
     * `onCreate`. A second shared call site is how the post-reboot window came back the first
     * time.
     *
     * The flavour bootstraps call it too, from `start()`, and that is the safety net the
     * comments in both `Specific` files describe -- it was `Specific.start()` doing it before.
     * So the assertion is about shared callers, with the safety net checked on both sides
     * rather than assumed.
     */
    @Test
    fun sharedCodeCallsRegisterBridgesOnceFromOnCreate() {
        val shared = filesMatching(Regex("""Specific\.registerBridges\(\)"""))
            .filter { it.contains("/src/main/") }
        assertEquals(
            "shared code must call registerBridges() from one place only; found $shared",
            listOf("Common/src/main/java/tk/glucodata/Applic.java"),
            shared,
        )
        mustContain(applic, "Specific.registerBridges();", "Applic")
        for (flavour in listOf("phone", "watch")) {
            mustContain(
                read("Common/src/${sourceSet(flavour)}/java/tk/glucodata/${bootstrapClass(flavour)}.java"),
                "Specific.registerBridges();",
                "$flavour bootstrap start()",
            )
        }
    }

    private fun bootstrapClass(flavour: String): String =
        if (flavour == "phone") "MobileVariantBootstrap" else "WearVariantBootstrap"

    /** The phone's source set is `mobile`; the flavour is called "phone" in these tests. */
    private fun sourceSet(flavour: String): String = if (flavour == "phone") "mobile" else "wear"

    /**
     * `blockedNum` is gone.
     *
     * It had no caller in any compiled source set or in native code, and it was the one member
     * that differed in kind between the flavours: empty on the phone, a help dialog on the
     * watch. #477 left it on the bootstrap classes as a static so that the diff would stay about
     * the rename, and this is the follow-up that removes it.
     *
     * Asserted by scanning the tree rather than by checking one file, so the test is a ratchet
     * instead of a snapshot: if it ever comes back, this is where that shows up.
     */
    @Test
    fun blockedNumIsGone() {
        val remaining = filesMatching(Regex("""\bblockedNum\b"""))
        assertEquals(
            "blockedNum is dead code and is not in the contract, so it should be absent " +
                "entirely; found it in $remaining",
            emptyList<String>(),
            remaining,
        )
    }

    // ---- helpers ---------------------------------------------------------------------

    /**
     * Names of the members declared directly in [className]'s body -- methods and fields
     * together, static or not.
     *
     * Depth, not indentation or the `static` keyword, is what decides membership. Both shapes
     * here would mislead a cheaper rule: `Specific.registerBridges()` contains an anonymous
     * class whose `public` method is not a member of `Specific`, while
     * `MobileVariantBootstrap` declares its overrides `public` and its `blockedNum` `static`.
     * Counting only depth-1 lines handles both without a per-class prefix list.
     */
    private fun declaredMembers(
        source: String,
        className: String,
        flavour: String,
    ): List<String> {
        val body = source.substringAfter("class $className")
        require(body.contains('{') && body.lastIndexOf('}') > body.indexOf('{')) {
            "could not find the $className class body in the $flavour source; if the class " +
                "was renamed or reformatted, fix the anchor here rather than the assertion"
        }
        var depth = 0
        val names = mutableListOf<String>()
        for (raw in body.substring(0, body.lastIndexOf('}')).lines()) {
            val line = raw.trim()
            if (depth == 1 && (line.startsWith("static ") || line.startsWith("public ")) &&
                !line.startsWith("//")
            ) {
                val name = Regex("""\b(\w+)\s*\(""").find(line)
                    ?: Regex("""\b(\w+)\s*=""").find(line)
                name?.let { names.add(it.groupValues[1]) }
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
        }
        return names.distinct()
    }

    /**
     * The nearest ancestor of the working directory that holds `Common/src`. Gradle runs the
     * test from the module directory, and the assertions quote repository-relative paths
     * because that is how a reader will look the file up.
     */
    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("no ancestor of user.dir holds Common/src")
    }

    /** Source files under `Common/src` with a line matching [pattern], repository-relative. */
    private fun filesMatching(
        pattern: Regex,
        skipResourcesAndComments: Boolean = false,
    ): List<String> = File(root(), "Common/src")
        .walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .filter { file ->
            file.useLines { lines ->
                lines.any { line ->
                    val text = line.trim()
                    if (skipResourcesAndComments &&
                        (text.startsWith("//") || text.startsWith("*") ||
                            text.startsWith("/*") || pattern.containsMatchIn("R.string.useclose"))
                    ) {
                        false
                    } else {
                        pattern.containsMatchIn(line)
                    }
                }
            }
        }
        .map { it.relativeTo(root()).path.replace(File.separatorChar, '/') }
        .sorted()
        .toList()

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = dir.resolve(relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("not found: $relative")
    }

    private fun mustContain(source: String, needle: String, what: String) {
        assertTrue("$what does not contain \"$needle\"; the source moved and this anchor is " +
            "stale, so fix the anchor rather than the assertion", source.contains(needle))
    }
}
