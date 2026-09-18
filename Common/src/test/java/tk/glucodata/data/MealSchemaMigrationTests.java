package tk.glucodata.data;

import java.sql.Connection;
import org.junit.Test;
import static org.junit.Assert.*;
import static tk.glucodata.data.CloneRecoveryMigrationTests.*;

public class MealSchemaMigrationTests {
    @Test public void mainUpgradeBuildsMealSchemaWithoutChangingJournalData() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            exec(actual, "INSERT INTO journal_entries(id,timestamp,entryType,title,source,createdAt,updatedAt) VALUES(7,1000,'note','Keep me','manual',900,1100)");
            exec(actual, "DROP TABLE meals");
            exec(actual, "DROP TABLE meal_items");
            exec(actual, "DROP TABLE meal_products");
            exec(actual, "DROP INDEX index_journal_entries_mealId");
            exec(actual, "ALTER TABLE journal_entries DROP COLUMN mealId");
            for (int version = 32; version < 36; version++) migrate(actual, version);
            assertEquals(schema(expected), schema(actual));
            assertEquals("Keep me", query(actual, "SELECT title FROM journal_entries WHERE id=7").get(0).get(0));
        }
    }
    @Test public void existingMealPreviewSchemaCanRunTheNewStepsAgain() throws Exception {
        try (Connection expected = db(); Connection actual = db()) {
            exec(actual, "INSERT INTO meals(id,label,createdAt,updatedAt) VALUES(7,'Keep meal',900,1100)");
            for (int version = 32; version < 36; version++) migrate(actual, version);
            assertEquals(schema(expected), schema(actual));
            assertEquals("Keep meal", query(actual, "SELECT label FROM meals WHERE id=7").get(0).get(0));
        }
    }
}
