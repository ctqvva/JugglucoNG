package tk.glucodata

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal command's entry identity is only useful if both sides actually send it, and neither
 * the encoder nor the phone's bridge can be reached from a JVM test: the codec tests build their
 * own frames, and `applyCommand` needs a Room database. So the wiring is pinned by reading the two
 * sources, the way `SpecificCompositionRootTests` and `WearMessagePathManifestTests` do.
 *
 * This is the weaker kind of check and it is here because the stronger one is not available yet: a
 * `JournalRepository.upsertEntry` test needs a real database, and no test calls `upsertEntry` at
 * all today. What the codec tests *do* pin is the decode side, in both directions, including that a
 * frame from a build that predates the field still decodes and carries no identity.
 */
class WearJournalIdentityWiringTests {

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/WearJournalSync.kt").exists() }
            ?: working
    }

    private fun source(relative: String) = File(moduleRoot, relative).readText()

    @Test
    fun theWatchAppendsTheIdentityToItsCommand() {
        val text = source("src/main/java/tk/glucodata/WearJournalSync.kt")
        val send = text.substringAfter("private fun sendCommand(")
        assertTrue(
            "sendCommand must carry the identity field, or a re-sent add cannot be de-duplicated",
            send.contains("entryIdentity: String? = null") &&
                send.contains("putShort(identity.size.toShort()).put(identity)"),
        )
    }

    @Test
    fun thePhoneStoresTheIdentityWhereUpsertMatchesIt() {
        val text = source("src/mobile/java/tk/glucodata/data/journal/WearJournalBridge.kt")
        assertTrue(
            "the watch's identity has to reach the row's sourceRecordId: it is the unique-indexed " +
                "field upsertEntry already matches, and the only thing that makes a repeat idempotent",
            text.contains("sourceRecordId = command.entryIdentity.takeIf { it.isNotEmpty() }"),
        )
    }

    @Test
    fun thePhoneEchoesTheIdentityBackWhenItServesTheJournal() {
        val text = source("src/mobile/java/tk/glucodata/data/journal/WearJournalBridge.kt")
        val encode = text.substringAfter("private fun encode(")
        assertTrue(
            "the watch cannot recognise its own entry in a served journal without the echo",
            encode.contains("putShort(identities.size.toShort())") &&
                encode.contains("buffer.put(identity.size.toByte())") &&
                encode.contains("entry.sourceRecordId"),
        )
    }

    @Test
    fun theOutboxIsPersistedAndTheRetryIsGatedOnTheEcho() {
        val text = source("src/main/java/tk/glucodata/WearJournalSync.kt")
        assertTrue(
            "an add the phone has not confirmed has to survive a restart, or it is lost the moment " +
                "the watch app is killed",
            text.contains("""putString(KEY_OUTBOX, encodeOutbox(items))"""),
        )
        assertTrue(
            "an add that has already been sent may only be sent again against a phone that echoes " +
                "identities; without the check a re-send against an older phone is a second row",
            text.contains("val mayRepeat = cached?.identityEcho == true") &&
                text.contains("if (repeat && !mayRepeat(item, mayRepeat)) return@map item"),
        )
        assertTrue(
            "saved must mean persisted: sendAdd returning the transport result is the bug #502 is about",
            text.contains("fun sendAdd(") && text.contains("storeOutbox(outbox() + item)"),
        )
        assertTrue(
            "a serve is the only proof available, so it has to be the thing that empties the queue",
            text.contains("val kept = outbox().filterNot { confirmedBy(served, it) }") &&
                text.contains("reconcile(journal)"),
        )
    }

    @Test
    fun theseChecksAreReadingTheFilesTheyName() {
        // A regex that has stopped matching is a test that passes by finding nothing, which is
        // worse than no test at all.
        assertTrue(
            "the sources moved, or the three checks above are vacuous",
            source("src/main/java/tk/glucodata/WearJournalSync.kt").contains("WearJournalSync") &&
                source("src/mobile/java/tk/glucodata/data/journal/WearJournalBridge.kt")
                    .contains("object WearJournalBridge"),
        )
    }
}
