package tk.glucodata.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingDisplayRevisionTests {
    private fun database(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE reading_display(timestamp INTEGER PRIMARY KEY, sensorSerial TEXT NOT NULL, displayMgdl REAL NOT NULL, viewMode INTEGER NOT NULL, calibrationFingerprint INTEGER NOT NULL, recordedAt INTEGER NOT NULL)")
                it.execute("INSERT INTO reading_display VALUES(60000, 'A', 120, 0, 7, 100)")
                it.execute("CREATE TABLE writes(n INTEGER NOT NULL)")
                it.execute("INSERT INTO writes VALUES(0)")
                it.execute("CREATE TRIGGER count_writes AFTER UPDATE ON reading_display BEGIN UPDATE writes SET n=n+1; END")
            }
        }
    }

    private fun revise(db: Connection, overrides: Map<String, Any> = emptyMap()): Int {
        // Execute the production SQL, including its seal and change guards.
        val source = File("src/mobile/java/tk/glucodata/data/ReadingDisplayDao.kt").readText()
        val sql = source.substringAfter("UPDATE reading_display").substringBefore("\"\"\"")
            .let { "UPDATE reading_display$it" }
        val args = mapOf<String, Any>(
            "timestamp" to 60000L, "sensorSerial" to "A", "displayMgdl" to 120f,
            "viewMode" to 0, "calibrationFingerprint" to 7L,
            "recordedAt" to 200L, "sealHorizon" to 0L,
        ) + overrides
        val parameters = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.toList()
        return db.prepareStatement(sql.replace(Regex(":([A-Za-z]+)"), "?")).use { statement ->
            parameters.forEachIndexed { index, name -> statement.setObject(index + 1, args.getValue(name)) }
            statement.executeUpdate()
        }
    }

    @Test fun unchangedPresentationDoesNotWriteOrChangeRecordedTime() = database().use { db ->
        repeat(3) { assertEquals(0, revise(db)) }
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT recordedAt, (SELECT n FROM writes) FROM reading_display").use {
                it.next()
                assertEquals(100L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
        }
    }

    @Test fun changedValueAndProvenanceStillReviseUnsealedMinutes() {
        listOf(
            mapOf("displayMgdl" to 121f), mapOf("sensorSerial" to "B"),
            // Same-lane presentation change (auto → auto+raw): still a revision.
            mapOf("viewMode" to 2), mapOf("calibrationFingerprint" to 8L),
        ).forEach { change ->
            database().use { db ->
                assertEquals(1, revise(db, change))
                assertEquals(0, revise(db, change))
            }
        }
    }

    @Test fun aViewModeSwitchRevisesUnsealedMinutesLikeACalibrationChange() {
        // Unsealed records are never drawn: after a raw/auto switch the grace
        // hour is already on screen in the new lane. Refusing the revision
        // sealed those minutes with the old lane's numbers an hour later.
        listOf(1, 3).forEach { mode ->
            database().use { db ->
                assertEquals(1, revise(db, mapOf("viewMode" to mode, "displayMgdl" to 31.4f)))
            }
        }
    }

    @Test fun aViewModeSwitchCannotReachASealedMinute() {
        listOf(1, 3).forEach { mode ->
            database().use { db ->
                assertEquals(0, revise(db, mapOf("sealHorizon" to 60000L, "viewMode" to mode, "displayMgdl" to 31.4f)))
            }
        }
    }

    @Test fun sealedMinuteCannotBeChanged() = database().use { db ->
        assertEquals(0, revise(db, mapOf("sealHorizon" to 60000L, "displayMgdl" to 130f, "sensorSerial" to "B")))
    }

    @Test fun repeatedTogglesLeaveTheLastPresentationInsideTheGraceWindow() {
        database().use { db ->
            for ((index, requestedMode) in listOf(0, 1, 2, 3, 2, 1, 0, 3).withIndex()) {
                assertEquals(1, revise(db, mapOf("viewMode" to requestedMode, "displayMgdl" to 130f + index)))
            }
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT viewMode, displayMgdl FROM reading_display").use {
                    it.next()
                    assertEquals(3, it.getInt(1))
                    assertEquals(137f, it.getFloat(2), 0.001f)
                }
            }
        }
    }

    @Test fun minuteImmediatelyInsideGraceWindowCanChange() = database().use { db ->
        assertEquals(1, revise(db, mapOf("sealHorizon" to 59999L, "displayMgdl" to 130f)))
    }
}
