package tk.glucodata.data.journal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only a fingerstick is a blood-glucose measurement. A dose or a meal placed
 * by tapping the chart carries the sensor value it was anchored to, and must
 * never be read back as a meter reading — every chart tap became a calibration
 * point that said the sensor agreed with itself.
 */
class JournalGlucoseEntryQueryTests {
    @Test
    fun calibrationCandidatesAreFingersticksOnly() {
        val dao = File("src/mobile/java/tk/glucodata/data/journal/JournalDao.kt").readText()
        val query = dao.substringAfter("suspend fun getGlucoseEntriesSince").let { after ->
            dao.substring(0, dao.indexOf("suspend fun getGlucoseEntriesSince")).substringAfterLast("@Query(")
        }
        assertTrue(
            "the calibration source must select on entry type, not on the presence of a value",
            query.contains("entryType = 'fingerstick'"),
        )
        assertTrue(
            "the storage value must match JournalEntryType.FINGERSTICK",
            File("src/mobile/java/tk/glucodata/data/journal/JournalModels.kt").readText()
                .contains("FINGERSTICK(\"fingerstick\")"),
        )
    }
}
