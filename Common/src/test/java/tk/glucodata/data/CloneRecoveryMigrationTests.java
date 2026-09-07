package tk.glucodata.data;

import android.database.Cursor;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executes production Migration objects through a JDBC adapter, never copied SQL. */
public class CloneRecoveryMigrationTests {
    private static final Path ROOT = root();
    private static Path root() {
        Path path = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("Common/src"))) path = path.getParent();
        if (path == null) throw new AssertionError("Repository root not found");
        return path;
    }
    // Feature and integration branches place the same recovery schema at different versions.
    private static int migrationFor(String sql) throws Exception {
        String source = Files.readString(ROOT.resolve("Common/src/mobile/java/tk/glucodata/data/HistoryDatabase.kt"));
        int end = source.indexOf(sql);
        assertTrue("Migration SQL is present", end > 0);
        Matcher declarations = Pattern.compile("private val MIGRATION_(\\d+)_(\\d+)").matcher(source.substring(0,end));
        int version = -1;
        while (declarations.find()) version = Integer.parseInt(declarations.group(1));
        assertTrue(version > 0);
        return version;
    }
    private static int identityVersion() throws Exception { return migrationFor("ALTER TABLE journal_entries ADD COLUMN recoveryId TEXT"); }
    private static int tombstoneVersion() throws Exception { return migrationFor("CREATE TABLE IF NOT EXISTS clone_journal_recovery_tombstones"); }
    private static int receiptVersion() throws Exception { return migrationFor("CREATE TABLE IF NOT EXISTS clone_recovery_imports"); }
    private Connection db() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection db = DriverManager.getConnection("jdbc:sqlite::memory:");
        String source = Files.readString(ROOT.resolve("Common/build/generated/ksp/mobileRelease/kotlin/tk/glucodata/data/HistoryDatabase_Impl.kt"));
        source = source.substring(source.indexOf("override fun createAllTables"), source.indexOf("override fun dropAllTables"));
        Matcher sql = Pattern.compile("connection\\.execSQL\\(\"([^\"]*)\"\\)").matcher(source);
        int count = 0;
        while (sql.find()) { exec(db, sql.group(1)); count++; }
        assertTrue("Read generated Room schema", count > 20);
        return db;
    }
    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) { statement.execute(sql); }
    }
    private static List<List<String>> query(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            List<List<String>> rows = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) row.add(result.getString(i));
                rows.add(row);
            }
            return rows;
        }
    }
    private static Cursor cursor(Connection db, String sql) throws Exception {
        List<List<String>> rows = query(db, sql);
        List<String> columns = new ArrayList<>();
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) columns.add(result.getMetaData().getColumnLabel(i));
        }
        int[] position = {-1};
        return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "moveToNext": return ++position[0] < rows.size();
                case "moveToFirst": position[0] = 0; return !rows.isEmpty();
                case "getColumnIndex": return columns.indexOf(args[0]);
                case "getColumnIndexOrThrow": {
                    int index = columns.indexOf(args[0]);
                    if (index < 0) throw new IllegalArgumentException(String.valueOf(args[0]));
                    return index;
                }
                case "getString": return rows.get(position[0]).get((Integer) args[0]);
                case "getInt": return Integer.parseInt(rows.get(position[0]).get((Integer) args[0]));
                case "getLong": return Long.parseLong(rows.get(position[0]).get((Integer) args[0]));
                case "getCount": return rows.size();
                case "close": return null;
                default: throw new UnsupportedOperationException(method.toString());
            }
        });
    }
    private static void migrate(Connection db, int from) throws Exception {
        SupportSQLiteDatabase adapter = (SupportSQLiteDatabase) Proxy.newProxyInstance(
            SupportSQLiteDatabase.class.getClassLoader(), new Class<?>[]{SupportSQLiteDatabase.class}, (proxy, method, args) -> {
                if (method.getName().equals("execSQL") && args.length == 1) { exec(db, (String) args[0]); return null; }
                if (method.getName().equals("query") && args[0] instanceof String) return cursor(db, (String) args[0]);
                throw new UnsupportedOperationException(method.toString());
            });
        Class<?> database = Class.forName("tk.glucodata.data.HistoryDatabase");
        Field field = database.getDeclaredField("MIGRATION_" + from + "_" + (from + 1));
        field.setAccessible(true);
        ((Migration) field.get(null)).migrate(adapter);
    }
    private static SortedMap<String, String> schema(Connection db) throws Exception {
        SortedMap<String, String> schema = new TreeMap<>();
        for (List<String> row : query(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name")) {
            String name = row.get(0);
            for (List<String> col : query(db, "PRAGMA table_info('" + name + "')")) {
                // Column order and unspecified Room defaults are not schema identity.
                schema.put(name + "." + col.get(1), col.get(2) + ":" + col.get(3) + ":" + col.get(5));
            }
            for (List<String> index : query(db, "PRAGMA index_list('" + name + "')")) {
                if (index.get(1).startsWith("sqlite_autoindex")) continue;
                schema.put("index:" + index.get(1), index.get(2) + ":" + query(db, "PRAGMA index_info('" + index.get(1) + "')").stream().map(c -> c.get(2)).collect(java.util.stream.Collectors.toList()));
            }
        }
        return schema;
    }
    private static void beforeRecovery(Connection db) throws Exception {
        exec(db, "DROP TABLE clone_recovery_imports");
        exec(db, "DROP TABLE clone_journal_recovery_tombstones");
        exec(db, "DROP INDEX index_journal_entries_recoveryId");
        exec(db, "ALTER TABLE journal_entries DROP COLUMN recoveryId");
        exec(db, "ALTER TABLE clone_journal_tombstones DROP COLUMN recoveryId");
    }
    private static void seed(Connection db) throws Exception {
        exec(db, "INSERT INTO journal_entries(id,timestamp,entryType,title,source,createdAt,updatedAt,nsUploadedAt,lvUploadedAt) VALUES(7,1000,'note','Keep this note','manual',900,1100,1200,1300),(8,2000,'note','Second','manual',1900,2100,NULL,NULL)");
        Set<String> columns = new HashSet<>();
        for (List<String> column : query(db,"PRAGMA table_info(history_readings)")) columns.add(column.get(1));
        exec(db, "INSERT INTO history_readings(id,timestamp,sensorSerial,value,rawValue" +
            (columns.contains("source") ? ",source" : "") + (columns.contains("firstStoredAt") ? ",firstStoredAt" : "") +
            ") VALUES(9,1000,'SENSOR',120,119" + (columns.contains("source") ? ",'sensor'" : "") +
            (columns.contains("firstStoredAt") ? ",0" : "") + ")");
    }
    @Test public void recoveryChainRetainsRowsAndAssignsUniqueIdentities() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            beforeRecovery(actual);
            seed(actual);
            exec(actual,"INSERT INTO clone_journal_tombstones(entryId,deletedAt) VALUES(4,700)");
            for (int version = identityVersion(); version <= receiptVersion(); version++) migrate(actual, version);
            assertEquals(schema(expected), schema(actual));
            assertEquals(Arrays.asList(Arrays.asList("Keep this note","1200","1300")), query(actual,"SELECT title,nsUploadedAt,lvUploadedAt FROM journal_entries WHERE id=7"));
            List<List<String>> ids = query(actual,"SELECT recoveryId FROM journal_entries ORDER BY id");
            assertTrue(ids.get(0).get(0).matches("[0-9a-f]{32}"));
            assertTrue(ids.get(1).get(0).matches("[0-9a-f]{32}"));
            assertNotEquals(ids.get(0), ids.get(1));
            assertEquals("120.0", query(actual,"SELECT value FROM history_readings WHERE id=9").get(0).get(0));
            assertEquals("700", query(actual,"SELECT deletedAt FROM clone_journal_tombstones WHERE entryId=4").get(0).get(0));
        }
    }
    @Test public void duplicateRecoveryIdentityIsRejected() throws Exception {
        try (Connection actual = db()) {
            beforeRecovery(actual); seed(actual); migrate(actual,identityVersion());
            try {
                exec(actual,"UPDATE journal_entries SET recoveryId=(SELECT recoveryId FROM journal_entries WHERE id=7) WHERE id=8");
                fail("Duplicate journal recovery identity accepted");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("UNIQUE"));
            }
            assertEquals("2",query(actual,"SELECT COUNT(DISTINCT recoveryId) FROM journal_entries").get(0).get(0));
        }
    }
    @Test public void failedMigrationTransactionPreservesOriginalSchemaAndRows() throws Exception {
        try (Connection actual = db()) {
            beforeRecovery(actual); seed(actual);
            SortedMap<String,String> before = schema(actual);
            actual.setAutoCommit(false);
            try {
                migrate(actual,identityVersion());
                exec(actual,"INSERT INTO table_that_does_not_exist VALUES(1)");
                fail("Injected SQL failure did not fail");
            } catch (SQLException expected) { actual.rollback(); }
            finally { actual.setAutoCommit(true); }
            assertEquals(before,schema(actual));
            assertEquals("1300",query(actual,"SELECT lvUploadedAt FROM journal_entries WHERE id=7").get(0).get(0));
        }
    }
    @Test public void receiptTableRejectsDuplicateJobIds() throws Exception {
        try (Connection actual = db()) {
            exec(actual,"DROP TABLE clone_recovery_imports"); migrate(actual,receiptVersion());
            exec(actual,"INSERT INTO clone_recovery_imports(jobId,sha256) VALUES('job','first')");
            try { exec(actual,"INSERT INTO clone_recovery_imports(jobId,sha256) VALUES('job','second')"); fail("Duplicate receipt accepted"); }
            catch (SQLException expected) { assertTrue(expected.getMessage().contains("UNIQUE")); }
            assertEquals("first",query(actual,"SELECT sha256 FROM clone_recovery_imports").get(0).get(0));
        }
    }
    @Test public void receiptMigrationDoesNotChangeHistory() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            seed(actual);
            exec(actual,"DROP TABLE clone_recovery_imports");
            migrate(actual,receiptVersion());
            assertEquals(schema(expected),schema(actual));
            assertEquals("1300",query(actual,"SELECT lvUploadedAt FROM journal_entries WHERE id=7").get(0).get(0));
        }
    }
    @Test public void tombstoneMigrationRetainsIdentitiesAndRows() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            seed(actual);
            exec(actual,"UPDATE journal_entries SET recoveryId='0123456789abcdef0123456789abcdef' WHERE id=7");
            exec(actual,"INSERT INTO clone_journal_tombstones(entryId,deletedAt,recoveryId) VALUES(4,700,'abcdef0123456789abcdef0123456789')");
            exec(actual,"DROP TABLE clone_recovery_imports");
            exec(actual,"DROP TABLE clone_journal_recovery_tombstones");
            migrate(actual,tombstoneVersion());
            migrate(actual,receiptVersion());
            assertEquals(schema(expected),schema(actual));
            assertEquals("0123456789abcdef0123456789abcdef",query(actual,"SELECT recoveryId FROM journal_entries WHERE id=7").get(0).get(0));
            assertEquals("1300",query(actual,"SELECT lvUploadedAt FROM journal_entries WHERE id=7").get(0).get(0));
            assertEquals("abcdef0123456789abcdef0123456789",query(actual,"SELECT recoveryId FROM clone_journal_tombstones WHERE entryId=4").get(0).get(0));
        }
    }
}
