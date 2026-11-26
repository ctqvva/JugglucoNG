package tk.glucodata.data.journal

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Applic
import tk.glucodata.data.HistoryDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NightscoutJournalFollowerImporterTests.TestApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NightscoutJournalFollowerImporterTests {
    class TestApplication : Applic() {
        override fun onCreate() = Unit
    }

    private lateinit var database: HistoryDatabase
    private var previousApp: Applic? = null
    private val timestamp = 1_700_000_000_000L
    private val legacyId = JournalTreatmentUploader.datedIdentifier(42L, timestamp)

    @Before
    fun setUp() {
        previousApp = Applic.app
        Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
        database = HistoryDatabase.getInstance(Applic.app)
    }

    @After
    fun tearDown() {
        database.close()
        Applic.app = previousApp
    }

    private fun upload(identifier: String, dose: Double, basalNote: Boolean): JSONObject {
        val doc = JSONObject()
            .put("identifier", identifier)
            .put("date", timestamp)
            .put("app", "JugglucoNG")
            .put("enteredBy", "JugglucoNG")
            .put("type", "insulin")
            .put("insulinType", "Tresiba")
            .put("eventType", if (basalNote) "Note" else "Correction Bolus")
        if (basalNote) doc.put("isBasalInsulin", true)
        return doc.put(if (basalNote) "amount" else "insulin", dose)
    }

    private fun receive(vararg treatments: JSONObject): Int =
        NightscoutJournalFollowerImporter.importTreatments("NSF-test", JSONArray(treatments.toList()).toString())

    @Test
    fun aReplacementUpdatesTheExistingDoseAndOlderCopiesCannotRestoreIt() = runBlocking {
        val legacy = upload(legacyId, 5.0, basalNote = false).put("isBasalInsulin", true)
        val basalId = NightscoutInsulinIdentity.forUpload(legacyId, legacyId, basalNote = true)
        val basal = upload(basalId, 6.0, basalNote = true)
        val bolusId = NightscoutInsulinIdentity.forUpload(legacyId, basalId, basalNote = false)
        val bolus = upload(bolusId, 4.0, basalNote = false)
        val dao = database.journalDao()

        assertEquals(1, receive(legacy))
        val original = dao.getEntries().single()

        assertEquals(1, receive(basal))
        val replaced = dao.getEntries().single()
        assertEquals(original.id, replaced.id)
        assertEquals(original.sourceRecordId, replaced.sourceRecordId)
        assertEquals(basalId, replaced.nsRemoteId)
        assertEquals(6f, replaced.amount!!, 0f)

        assertEquals(0, receive(legacy, JSONObject().put("identifier", legacyId).put("isValid", false)))
        assertEquals(basalId, dao.getEntries().single().nsRemoteId)

        assertEquals(1, receive(bolus, legacy, basal))
        val restored = dao.getEntries().single()
        assertEquals(original.id, restored.id)
        assertEquals(bolusId, restored.nsRemoteId)
        assertEquals(4f, restored.amount!!, 0f)
    }
}
