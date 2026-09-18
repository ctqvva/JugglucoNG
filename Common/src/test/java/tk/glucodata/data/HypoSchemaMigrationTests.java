package tk.glucodata.data;

import java.sql.Connection;
import org.junit.Test;
import static org.junit.Assert.*;
import static tk.glucodata.data.CloneRecoveryMigrationTests.*;

public class HypoSchemaMigrationTests {
    @Test public void mainUpgradeCreatesOnlyHypoMarksAndIsIdempotent() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            exec(actual, "DROP TABLE hypo_episode_marks");
            migrate(actual, 32);
            assertEquals(schema(expected), schema(actual));
            exec(actual, "INSERT INTO hypo_episode_marks VALUES(1,2,65,'real','manual',3)");
            migrate(actual, 32);
            assertEquals(schema(expected), schema(actual));
            assertEquals("65.0", query(actual, "SELECT nadirMgdl FROM hypo_episode_marks WHERE episodeKeyMs=1").get(0).get(0));
        }
    }
}
