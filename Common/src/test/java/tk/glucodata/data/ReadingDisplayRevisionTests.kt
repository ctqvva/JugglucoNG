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
                it.execute("CREATE TABLE reading_display(timestamp INTEGER NOT NULL, sensorSerial TEXT NOT NULL, displayMgdl REAL NOT NULL, viewMode INTEGER NOT NULL, calibrationFingerprint INTEGER NOT NULL, recordedAt INTEGER NOT NULL, lane INTEGER NOT NULL, PRIMARY KEY(timestamp, lane))")
                // The same minute on both main lines: auto 120, raw 98.
                it.execute("INSERT INTO reading_display VALUES(60000, 'A', 120, 0, 7, 100, 0)")
                it.execute("INSERT INTO reading_display VALUES(60000, 'A', 98, 1, 7, 100, 1)")
                it.execute("CREATE TABLE writes(n INTEGER NOT NULL)")
                it.execute("INSERT INTO writes VALUES(0)")
                it.execute("CREATE TRIGGER count_writes AFTER UPDATE ON reading_display BEGIN UPDATE writes SET n=n+1; END")
            }
        }
    }

    private fun revise(db: Connection, overrides: Map<String, Any> = emptyMap()): Int {
        // Execute the production SQL, including its seal, lane and change guards.
        val source = File("src/mobile/java/tk/glucodata/data/ReadingDisplayDao.kt").readText()
        val sql = source.substringAfter("UPDATE reading_display").substringBefore("\"\"\"")
            .let { "UPDATE reading_display$it" }
        val viewMode = overrides["viewMode"] as Int? ?: 0
        val args = mapOf<String, Any>(
            "timestamp" to 60000L, "sensorSerial" to "A", "displayMgdl" to 120f,
            "viewMode" to viewMode, "calibrationFingerprint" to 7L,
            "recordedAt" to 200L, "sealHorizon" to 0L, "lane" to ReadingDisplay.laneOf(viewMode),
        ) + overrides
        val parameters = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.toList()
        return db.prepareStatement(sql.replace(Regex(":([A-Za-z]+)"), "?")).use { statement ->
            parameters.forEachIndexed { index, name -> statement.setObject(index + 1, args.getValue(name)) }
            statement.executeUpdate()
        }
    }

    private fun lanes(db: Connection): Map<Int, Pair<Int, Float>> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT lane, viewMode, displayMgdl FROM reading_display").use {
                val out = HashMap<Int, Pair<Int, Float>>()
                while (it.next()) out[it.getInt(1)] = it.getInt(2) to it.getFloat(3)
                out
            }
        }

    @Test fun unchangedPresentationDoesNotWriteOrChangeRecordedTime() = database().use { db ->
        repeat(3) { assertEquals(0, revise(db)) }
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT recordedAt, (SELECT n FROM writes) FROM reading_display WHERE lane = 0").use {
                it.next()
                assertEquals(100L, it.getLong(1))
                assertEquals(0, it.getInt(2))
            }
        }
    }

    @Test fun changedValueAndProvenanceStillReviseUnsealedMinutes() {
        listOf(
            mapOf("displayMgdl" to 121f), mapOf("sensorSerial" to "B"),
            // Same main line (auto → auto+raw): still a revision of its record.
            mapOf("viewMode" to 2), mapOf("calibrationFingerprint" to 8L),
        ).forEach { change ->
            database().use { db ->
                assertEquals(1, revise(db, change))
                assertEquals(0, revise(db, change))
            }
        }
    }

    @Test fun aRawPresentationRevisesOnlyTheRawLine() = database().use { db ->
        listOf(1, 3).forEachIndexed { index, mode ->
            assertEquals(1, revise(db, mapOf("viewMode" to mode, "displayMgdl" to 90f + index)))
        }
        val lanes = lanes(db)
        assertEquals("the auto line keeps what it showed", 0 to 120f, lanes[0])
        assertEquals(3 to 91f, lanes[1])
    }

    @Test fun anAutoPresentationRevisesOnlyTheAutoLine() = database().use { db ->
        assertEquals(1, revise(db, mapOf("viewMode" to 2, "displayMgdl" to 125f)))
        val lanes = lanes(db)
        assertEquals(2 to 125f, lanes[0])
        assertEquals("the raw line keeps what it showed", 1 to 98f, lanes[1])
    }

    @Test fun sealedMinuteCannotBeChangedInEitherLane() = database().use { db ->
        listOf(0, 1, 2, 3).forEach { mode ->
            assertEquals(
                0,
                revise(db, mapOf("sealHorizon" to 60000L, "viewMode" to mode, "displayMgdl" to 130f, "sensorSerial" to "B")),
            )
        }
        assertEquals(mapOf(0 to (0 to 120f), 1 to (1 to 98f)), lanes(db))
    }

    @Test fun repeatedTogglesLeaveEachLineItsOwnLastPresentation() = database().use { db ->
        for ((index, requestedMode) in listOf(0, 1, 2, 3, 2, 1, 0, 3).withIndex()) {
            assertEquals(1, revise(db, mapOf("viewMode" to requestedMode, "displayMgdl" to 130f + index)))
        }
        assertEquals(mapOf(0 to (0 to 136f), 1 to (3 to 137f)), lanes(db))
    }

    @Test fun minuteImmediatelyInsideGraceWindowCanChange() = database().use { db ->
        assertEquals(1, revise(db, mapOf("sealHorizon" to 59999L, "displayMgdl" to 130f)))
    }
}
