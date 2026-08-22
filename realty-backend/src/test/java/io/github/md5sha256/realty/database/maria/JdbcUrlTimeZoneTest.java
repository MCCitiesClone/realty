package io.github.md5sha256.realty.database.maria;

import io.github.md5sha256.realty.DatabaseSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

/**
 * The time-zone pinning ladder in {@link MariaDatabase#resolveJdbcUrl}.
 *
 * <p>Pinning by zone name is only understood by a server whose {@code mysql.time_zone*} tables have
 * been populated. Against one without them the connection is refused with "Unknown or incorrect
 * time zone", which took the whole plugin down at enable — so the pinning has to degrade instead of
 * failing. These tests drive the ladder through a stubbed connectivity check, which is what lets
 * the no-time-zone-tables case be covered at all without standing up such a server.
 */
class JdbcUrlTimeZoneTest {

    private static final DatabaseSettings SETTINGS =
            new DatabaseSettings("mysql://localhost:3306/realty", "user", "pass");

    private static final Logger SILENT = Logger.getLogger(JdbcUrlTimeZoneTest.class.getName());

    /** Accepts only URLs whose time zone is in {@code accepted}; records everything it was asked. */
    private static BiPredicate<String, DatabaseSettings> serverAccepting(List<String> attempts,
                                                                        String... accepted) {
        List<String> allowed = List.of(accepted);
        return (url, settings) -> {
            attempts.add(url);
            return allowed.stream().anyMatch(zone -> url.contains("connectionTimeZone=" + zone));
        };
    }

    private static String expectedOffset() {
        String offset = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getId();
        return offset.equals("Z") ? "+00:00" : offset;
    }

    @Test
    @DisplayName("pins by zone name when the server understands one")
    void prefersTheZoneName() {
        List<String> attempts = new ArrayList<>();
        String url = MariaDatabase.resolveJdbcUrl(SETTINGS, SILENT, serverAccepting(attempts, "LOCAL"));

        Assertions.assertTrue(url.contains("connectionTimeZone=LOCAL"), url);
        Assertions.assertTrue(url.contains("forceConnectionTimeZoneToSession=true"), url);
        Assertions.assertEquals(1, attempts.size(), "no need to probe further once the name works");
    }

    @Test
    @DisplayName("falls back to a fixed offset when the zone name is rejected")
    void fallsBackToTheOffset() {
        List<String> attempts = new ArrayList<>();
        String url = MariaDatabase.resolveJdbcUrl(SETTINGS, SILENT,
                serverAccepting(attempts, expectedOffset()));

        // An offset needs no lookup tables, so it works on a server that rejects the name.
        Assertions.assertTrue(url.contains("connectionTimeZone=" + expectedOffset()), url);
        Assertions.assertTrue(url.contains("forceConnectionTimeZoneToSession=true"), url);
        Assertions.assertEquals(2, attempts.size(), "should try the name before the offset");
    }

    @Test
    @DisplayName("gives up on pinning rather than refusing to start")
    void fallsBackToTheConfiguredUrl() {
        List<String> attempts = new ArrayList<>();
        String url = MariaDatabase.resolveJdbcUrl(SETTINGS, SILENT, serverAccepting(attempts));

        // Whatever the reason nothing connected — unreachable host, bad credentials, a server that
        // refuses both forms — startup must not hinge on the time zone. The real failure then
        // surfaces from the migration that follows, with its own error.
        Assertions.assertEquals("jdbc:" + SETTINGS.url(), url);
        Assertions.assertEquals(2, attempts.size());
    }

    @Test
    @DisplayName("leaves an explicitly configured time zone alone and does not probe")
    void honoursAnExplicitTimeZone() {
        DatabaseSettings explicit = new DatabaseSettings(
                "mysql://localhost:3306/realty?connectionTimeZone=UTC", "user", "pass");
        List<String> attempts = new ArrayList<>();

        String url = MariaDatabase.resolveJdbcUrl(explicit, SILENT, serverAccepting(attempts));

        Assertions.assertEquals("jdbc:" + explicit.url(), url);
        Assertions.assertTrue(attempts.isEmpty(), "the operator's choice is not second-guessed");
    }

    @Test
    @DisplayName("appends with & when the configured URL already has a query string")
    void appendsToAnExistingQueryString() {
        DatabaseSettings withQuery = new DatabaseSettings(
                "mysql://localhost:3306/realty?useSSL=false", "user", "pass");
        List<String> attempts = new ArrayList<>();

        String url = MariaDatabase.resolveJdbcUrl(withQuery, SILENT, serverAccepting(attempts, "LOCAL"));

        Assertions.assertTrue(url.contains("?useSSL=false&connectionTimeZone=LOCAL"), url);
    }
}
