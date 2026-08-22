package io.github.md5sha256.realty.database;

import io.github.md5sha256.realty.DatabaseSettings;
import io.github.md5sha256.realty.database.maria.MariaDatabase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Exercises the startup path the plugin actually takes.
 *
 * <p>{@link AbstractDatabaseTest} migrates by calling {@code MariaSchemaMigrator.migrate} with the
 * container's raw URL, so it never runs {@link MariaDatabase#initializeSchema} — and therefore never
 * touches the URL the plugin really connects with. Time-zone pinning was added to that URL and
 * broke enable on a live server while every test stayed green. This test closes that gap: it goes
 * through {@code MariaDatabase} exactly as {@code Realty.onEnable} does.
 */
class SchemaInitializationTest extends AbstractDatabaseTest {

    private static DatabaseSettings containerSettings(String urlSuffix) {
        // MariaDatabase prepends "jdbc:", matching how Realty builds settings from database.yml.
        String url = CONTAINER.getJdbcUrl().substring("jdbc:".length()) + urlSuffix;
        return new DatabaseSettings(url, CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    @Test
    @DisplayName("initializeSchema connects and migrates through the resolved URL")
    void initializeSchemaSucceeds() {
        MariaDatabase database = new MariaDatabase(containerSettings(""), Logger.getLogger("test"));

        // Migrations are guarded by schema_version, so running them again is a no-op — what is
        // under test is that the URL the pool and the migrator share can open a connection at all.
        Assertions.assertDoesNotThrow(() -> database.initializeSchema(Path.of("sql/migrations")));
        Assertions.assertDoesNotThrow(() -> {
            try (SqlSessionWrapper wrapper = database.openSession()) {
                wrapper.governmentPartyMapper().selectAll();
            }
        });
        database.close();
    }

    @Test
    @DisplayName("initializeSchema honours an explicitly configured time zone")
    void initializeSchemaWithExplicitTimeZone() {
        // An operator who pins the zone themselves is taken at their word and never probed, so this
        // has to reach the server unaltered and still work.
        MariaDatabase database = new MariaDatabase(
                containerSettings("?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"),
                Logger.getLogger("test"));

        Assertions.assertDoesNotThrow(() -> database.initializeSchema(Path.of("sql/migrations")));
        database.close();
    }
}
